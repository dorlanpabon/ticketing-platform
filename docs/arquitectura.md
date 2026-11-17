# Arquitectura

Catalog administra los eventos. Orders recibe compras, confirmaciones y cortesías. Inventory asigna las entradas y calcula la disponibilidad. Cada servicio tiene su propia tabla DynamoDB, una API WebFlux y un proceso de trabajo que puede escalar aparte. Los casos de uso del Scaffold Clean Architecture se conectan a HTTP, DynamoDB y SQS mediante adaptadores.

## Servicios y comunicación

```mermaid
flowchart LR
    U[Comprador o administrador] -->|Crear y consultar eventos| C[Catalog]
    U -->|Enviar y consultar órdenes| O[Orders]
    U -->|Consultar disponibilidad| I[Inventory]
    C --> CD[(DynamoDB Eventos)]
    O --> OD[(DynamoDB Órdenes)]
    I --> ID[(DynamoDB Entradas y reservas)]
    C -->|Alta de inventario| Q[SQS Standard]
    O -->|Compra, confirmación o cortesía| Q
    Q -->|Comandos y vencimientos| I
    I -->|Inventario listo o cambio de reserva| Q
    Q -->|Activación| C
    Q -->|Resultado de la reserva| O
    O -->|Consulta y conciliación por HTTP privado| I
```

### Mensajería y datos en AWS

![Topología en AWS de las colas SQS, sus DLQ, las tablas DynamoDB por servicio y EventBridge Scheduler](diagramas/mensajeria-y-datos.png)

| Cola | Quién publica y quién la lee |
| --- | --- |
| `inventory-commands` | Catalog solicita crear las entradas. Orders pide reservar, confirmar o emitir una cortesía. Inventory ejecuta los cambios. |
| `inventory-expirations` | Inventory publica el temporizador de cada reserva. EventBridge Scheduler envía el aviso de recuperación en AWS. Inventory procesa ambos. |
| `catalog-events` | Inventory avisa que las entradas ya existen. Catalog activa el evento. |
| `order-events` | Inventory publica el estado y la versión de la reserva. Orders actualiza su proyección. |

Cada cola tiene una DLQ para los mensajes que agotan sus reintentos. Solo Inventory cambia las entradas. Orders consulta el estado vigente y concilia órdenes pendientes mediante HTTP privado. En Docker Compose, LocalStack ofrece SQS, DynamoDB Local almacena los datos y un contenedor publica el aviso periódico de recuperación.

## Alta de un evento

```mermaid
sequenceDiagram
    actor A as Administrador
    participant C as Catalog
    participant Q as SQS
    participant I as Inventory

    A->>C: POST /api/v1/events
    C->>C: Guardar evento PROVISIONING y comando en una transacción
    C-->>A: 202 con eventId
    C->>Q: ProvisionInventory en inventory-commands
    Q->>I: Entregar petición de alta
    I->>I: Crear las entradas numeradas
    I->>Q: InventoryReady en catalog-events
    Q->>C: Entregar resultado del alta
    C->>C: Cambiar el evento a ACTIVE
```

## Compra y vencimiento

```mermaid
sequenceDiagram
    actor U as Comprador
    participant O as Orders
    participant Q as SQS
    participant I as Inventory

    U->>O: POST /api/v1/orders con Idempotency-Key
    O->>O: Guardar orden, plazo y comando en DynamoDB
    O->>Q: Publicar PurchaseRequested
    alt Envío confirmado
        Q-->>O: Confirmar recepción
        O-->>U: 202 con orderId
    else Envío sin confirmación
        O-->>U: 503 ORDER_ENQUEUE_UNAVAILABLE
        O->>Q: El proceso de trabajo reintenta el mensaje del outbox
    end
    Q->>I: Entregar solicitud de reserva
    I->>I: Reservar todas las entradas o rechazar el conjunto
    par Notificación del resultado
        I->>Q: ReservationStateChanged en order-events
        Q->>O: Actualizar la proyección de la orden
    and Temporizador si se creó la reserva
        I->>Q: Encolar ExpireReservation con el tiempo restante
    end
    opt Entradas reservadas
        alt Confirmación dentro del plazo
            U->>O: POST /api/v1/orders/{orderId}/confirm
            O->>O: Guardar confirmación en el outbox
            O-->>U: 202 con el estado observado
            O->>Q: ConfirmPurchase en inventory-commands
            Q->>I: Entregar la confirmación
            I->>I: Verificar estado y plazo antes de vender
        else Plazo alcanzado
            Q->>I: Entregar ExpireReservation
            I->>I: Liberar las entradas aún retenidas
        end
        I->>Q: ReservationStateChanged en order-events
        Q->>O: Actualizar la proyección de la orden
    end
```

**Persistencia y concurrencia.** Orders guarda la orden y el comando en una transacción. Ese outbox permite recuperar un envío tras una caída. El `202` confirma que se aceptó el envío a SQS. La asignación se consulta después con el `orderId`. Inventory reserva todas las entradas solicitadas o rechaza el conjunto. Las condiciones de estado y versión impiden que dos compras se queden con la misma entrada.

**Plazo.** Con la configuración local, la orden conserva `expiresAt = requestedAt + 600 s`. SQS entrega el temporizador tras el tiempo restante. Un aviso cada minuto permite que Inventory recupere vencimientos pendientes consultando el índice `gsi2` por fecha. Inventory libera las entradas solo si la reserva venció y sigue en `RESERVED` o `PENDING_CONFIRMATION`. `SOLD` y `COMPLIMENTARY` son finales.

**Cortesías.** Orders acepta la solicitud de un administrador y envía `ComplimentaryRequested`. Inventory asigna las entradas disponibles en una transacción y devuelve el nuevo estado a Orders por `order-events`. La disponibilidad cuenta las cortesías aparte de las ventas.

**Reintentos y acceso.** SQS puede entregar un mensaje más de una vez. Los consumidores verifican identificadores y versiones antes de aplicar el cambio. Repetir una compra con los mismos datos y la misma clave conserva la orden y el plazo. Usar esa clave para otra selección devuelve `409`. Consultar como otro comprador devuelve `404`. Crear eventos y cortesías exige el rol de administrador.

**Seguridad.** La compra admite hasta diez entradas. En AWS, API Gateway valida JWT y limita la frecuencia de solicitudes. Las tareas privadas obtienen credenciales de sus roles IAM. El único permiso SQS de Orders API es `sqs:SendMessage` sobre `inventory-commands`.

**Límites y producción.** LocalStack recrea las colas vacías después de un reinicio. El ejercicio cubre reserva, confirmación y cortesía. Antes de operar en AWS, mediría la carga por partición y la antigüedad de los mensajes, y ensayaría las alarmas y la recuperación del inventario.

Los contratos [OpenAPI](../contracts/openapi.json) y [AsyncAPI](../contracts/asyncapi.json) describen las solicitudes HTTP y los mensajes. El [repositorio de infraestructura](../../ticketing-infra/README.md) explica la red, el aislamiento, los costos y la operación en AWS. Su [diagrama editable](../../ticketing-infra/docs/diagramas/infraestructura-aws.drawio) se abre en [diagrams.net](https://app.diagrams.net/).
