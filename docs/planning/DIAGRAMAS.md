# Diagramas de la plataforma de ticketing

**Estado: DISEÑO PROPUESTO. No representa una implementación ni un despliegue comprobados.**  
Fecha: 16 de noviembre de 2026. Todos los diagramas describen las decisiones del plan desde cero.

Los bloques Mermaid son editables. La representación de contexto y contenedores sigue los niveles conceptuales C4, utilizando sintaxis Mermaid estándar. Los ambientes, colas, bases y procesos dibujados son componentes previstos, no recursos existentes.

| N.º | Diagrama | Pregunta que permite responder |
|---|---|---|
| 1 | Contexto | ¿Quién utiliza la plataforma y qué queda dentro de su responsabilidad? |
| 2 | Contenedores y propietarios | ¿Dónde vive cada dato y cómo se comunican los servicios? |
| 3 | Reserva y aceptación durable | ¿Qué garantiza el `202` y cómo se recupera una reserva? |
| 4 | Confirmación contra expiración | ¿Cómo se evita vender y liberar las mismas entradas? |
| 5 | Máquina de estados | ¿Qué transiciones son válidas y cuáles son finales? |
| 6 | Ramas y promoción | ¿Cómo llega una funcionalidad desde `develop` hasta `main`? |
| 7 | Calidad por commit | ¿Cómo se valida cada SHA y dónde bloquea Sonar? |
| 8 | Despliegue AWS | ¿Cómo operan los servicios privados y aislados por ambiente? |

## 1. Contexto conceptual C4

```mermaid
flowchart LR
    Buyer["Comprador autenticado"]
    Admin["Administrador autorizado"]
    Reviewer["Evaluador técnico"]
    Platform["Plataforma de ticketing<br/>Eventos, reservas, compras y cortesías"]
    Identity["Proveedor de identidad<br/>Local en demo; Cognito en AWS"]
    Evidence["Documentación y evidencia<br/>Contratos, pruebas, calidad y diagramas"]

    Buyer -->|"Consultar, reservar y confirmar"| Platform
    Admin -->|"Crear eventos y asignar cortesías"| Platform
    Buyer -->|"Autenticarse"| Identity
    Admin -->|"Autenticarse"| Identity
    Identity -->|"Identidad verificable"| Platform
    Reviewer -->|"Reproducir escenarios"| Platform
    Reviewer -->|"Revisar decisiones y resultados"| Evidence
    Platform -.->|"Evidencias generadas durante la implementación"| Evidence
```

La plataforma no incluye cobros reales, frontend de venta ni integración con sistemas internos de Nequi. El comprador se identifica mediante autenticación; no puede elegir su identidad enviando un campo en el cuerpo de la solicitud.

## 2. Contenedores, datos y mensajería

```mermaid
flowchart TB
    Client["Cliente o colección de pruebas"]
    Entry["Entrada autenticada<br/>Rutas públicas autorizadas"]

    subgraph CatalogBoundary["Microservicio Catálogo"]
        Catalog["API WebFlux y consumidor<br/>Publicación de eventos"]
        CatalogData[("Datos de Catálogo<br/>Eventos, inbox y outbox")]
        CatalogRelay["Relay de outbox"]
        Catalog -->|"Transacción local"| CatalogData
        CatalogData --> CatalogRelay
    end

    subgraph OrderBoundary["Microservicio Órdenes"]
        Orders["API WebFlux y coordinador<br/>Intenciones y seguimiento de saga"]
        OrderData[("Datos de Órdenes<br/>Órdenes, idempotencia, inbox y outbox")]
        OrderRelay["Relay de outbox"]
        Orders -->|"Transacción local"| OrderData
        OrderData --> OrderRelay
    end

    subgraph InventoryBoundary["Microservicio Inventario"]
        Inventory["API WebFlux y consumidor<br/>Autoridad sobre entradas y reservas"]
        InventoryData[("Datos de Inventario<br/>Entradas, reservas, 16 contadores por evento<br/>Auditoría, inbox y outbox")]
        InventoryRelay["Relay de outbox"]
        Expirer["Worker de expiración<br/>Consulta por vencimiento; sin Scan"]
        Inventory -->|"Transacciones condicionales"| InventoryData
        Expirer -->|"Liberación de reserva completa"| InventoryData
        InventoryData --> InventoryRelay
    end

    Commands["SQS Standard<br/>inventory-commands"]
    OrderEvents["SQS Standard<br/>order-events"]
    CatalogEvents["SQS Standard<br/>catalog-events"]
    CommandsDLQ["DLQ de comandos"]
    OrdersDLQ["DLQ de eventos de Órdenes"]
    CatalogDLQ["DLQ de eventos de Catálogo"]

    Client --> Entry
    Entry --> Catalog
    Entry --> Orders
    Entry -->|"Solo consultas públicas"| Inventory
    Orders -->|"Reserva idempotente y consulta internas"| Inventory
    CatalogRelay -->|"Preparar inventario"| Commands
    OrderRelay -->|"Comprar, confirmar o asignar cortesía"| Commands
    Commands --> Inventory
    InventoryRelay -->|"Snapshot versionado de reserva"| OrderEvents
    OrderEvents --> Orders
    InventoryRelay -->|"Inventario preparado"| CatalogEvents
    CatalogEvents --> Catalog
    Commands -.->|"Tras reintentos agotados"| CommandsDLQ
    OrderEvents -.->|"Tras reintentos agotados"| OrdersDLQ
    CatalogEvents -.->|"Tras reintentos agotados"| CatalogDLQ
```

Cada microservicio posee sus datos y permisos. No hay escrituras ni transacciones entre tablas de propietarios distintos. Los relays y consumidores son roles del servicio correspondiente; el dibujo no exige desplegarlos como microservicios adicionales.

Todos los productores utilizan outbox. Una transacción de Inventario incluye la reserva, sus entradas, los contadores afectados, auditoría y evento. La garantía es de efectos de negocio idempotentes sobre entrega al menos una vez.

## 3. Crear una orden y reservar sin perder la intención

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Comprador
    participant Orders as Órdenes
    participant ODB as Datos de Órdenes
    participant Inventory as Inventario
    participant IDB as Datos de Inventario
    participant Relay as Relay de Órdenes
    participant Queue as SQS de comandos

    Customer->>Orders: POST /orders + Idempotency-Key
    Orders->>Orders: Validar identidad y 1..10 entradas distintas
    Orders->>ODB: Transacción: orden RESERVING + deadline + idempotencia + outbox
    ODB-->>Orders: Intención durable, reservationId = orderId

    Orders->>Inventory: PUT reserva idempotente, presupuesto HTTP de 500 ms
    alt Resultado conocido dentro del presupuesto
        Inventory->>IDB: Reserva condicional todo o nada + contadores + auditoría + outbox
        IDB-->>Inventory: Resultado durable de éxito o rechazo
        Inventory-->>Orders: Snapshot y versión autoritativos
        Orders->>ODB: Aplicar snapshot si su versión es posterior
    else Timeout o dependencia inaccesible
        Note over Orders,Inventory: El resultado es desconocido, no cancelar por suposición
    end

    Orders-->>Customer: 202 + orderId + estado observado + URL de consulta
    Note over Customer,Orders: 202 garantiza trabajo persistido, puede continuar RESERVING
    Note over Relay,Queue: La publicación puede ocurrir antes o después de la respuesta, no se espera para aceptar

    Relay->>ODB: Reclamar outbox pendiente con lease
    Relay->>Queue: Publicar PurchaseRequested con identidad estable
    Queue-->>Relay: Publicación confirmada
    Relay->>ODB: Registrar entrega del outbox
    Queue->>Inventory: Entregar comando, puede estar duplicado
    Inventory->>IDB: Ejecutar la misma operación idempotente y verificar deadline
    IDB-->>Inventory: Misma reserva o resultado terminal, sin doble descuento
    Inventory->>Queue: ACK después de persistir el resultado
    Note over Inventory,IDB: Su outbox publica el snapshot hacia Órdenes
```

La publicación y el intento HTTP pueden competir. Ambos utilizan el mismo identificador y contenido canónico. El plazo se fija al aceptar la solicitud y no se reinicia al consumirla. Un rechazo también se registra para impedir que una redelivery tardía reserve una orden ya rechazada.

La respuesta `202` no afirma que SQS ya recibió el mensaje si este permanece en outbox. El relay recupera publicaciones pendientes cuando la cola vuelve a estar disponible.

## 4. Confirmación, expiración y recuperación del ACK

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Comprador
    participant Orders as Órdenes
    participant ODB as Datos de Órdenes
    participant Queue as SQS de comandos
    participant Inventory as Inventario
    participant IDB as Datos de Inventario
    participant Expirer as Worker de expiración

    Customer->>Orders: POST /orders/{id}/confirm
    Orders->>Inventory: Consultar reserva autoritativa y comprobar propietario
    Inventory-->>Orders: Reserva vigente y estado actual
    Orders->>ODB: Transacción: intención de confirmación + outbox
    Orders-->>Customer: 202, confirmación aceptada para procesamiento
    Note over ODB,Queue: El relay publica ConfirmPurchase con commandId estable por orden
    Queue->>Inventory: Entrega de ConfirmPurchase
    Inventory->>IDB: Transacción condicional RESERVED a PENDING_CONFIRMATION
    IDB-->>Inventory: Transición, auditoría y outbox durables

    par Intento de completar la venta
        Inventory->>IDB: Intentar SOLD si reserva, versión y deadline siguen vigentes
    and Intento de liberar cuando vence
        Expirer->>IDB: Intentar AVAILABLE si deadline alcanzado y estado no final
    end

    alt Gana la venta válida
        IDB-->>Inventory: SOLD, entradas y contadores actualizados atómicamente
        IDB-->>Expirer: Condición no satisfecha, releer estado final y no liberar
    else Gana la expiración
        IDB-->>Expirer: Reserva completa liberada + auditoría + outbox
        IDB-->>Inventory: Condición no satisfecha, resultado EXPIRED, sin venta
    end

    Inventory->>Queue: ACK únicamente después del resultado terminal durable
    opt Caída posterior al commit y anterior al ACK
        Queue->>Inventory: Redelivery del mismo comando
        Inventory->>IDB: Consultar inbox y resultado terminal
        IDB-->>Inventory: Resultado existente, no repetir efectos
        Inventory->>Queue: ACK
    end
```

La confirmación y la expiración compiten mediante condiciones sobre la misma reserva y sus entradas. Una operación no puede liberar la asignación de otra reserva posterior. Cambiar a `PENDING_CONFIRMATION` no amplía los diez minutos.

El reloj se inyecta para pruebas y cada intento comprueba el plazo. El sistema no promete exactitud física al milisegundo: el barrido tiene un objetivo de retraso medible y ninguna demora de barrido autoriza una confirmación vencida.

## 5. Estados oficiales de una entrada

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE
    AVAILABLE --> RESERVED: Reserva condicional de todas las entradas
    RESERVED --> PENDING_CONFIRMATION: Confirmación explícita y vigente
    PENDING_CONFIRMATION --> SOLD: Consumidor confirma dentro del plazo
    RESERVED --> AVAILABLE: Reserva vencida; liberación completa
    PENDING_CONFIRMATION --> AVAILABLE: Plazo vencido antes de vender
    AVAILABLE --> COMPLIMENTARY: Cortesía administrativa autorizada
    SOLD --> [*]
    COMPLIMENTARY --> [*]
```

- `SOLD` y `COMPLIMENTARY` son finales; las flechas al marcador final no significan borrado del registro.
- `RESERVED` y `PENDING_CONFIRMATION` no son ventas.
- `COMPLIMENTARY` ocupa capacidad sin incrementar ventas.
- `RESERVING`, `REJECTED` y `EXPIRED` pertenecen al seguimiento de la orden, no agregan estados al enum de entradas.

Invariante a comprobar tras las operaciones:

```text
capacity = available + reserved + pendingConfirmation + sold + complimentary
```

## 6. Ramas, congelación y promoción del candidato

```mermaid
flowchart LR
    MainBase["main<br/>Base inicial estable"]
    Develop["develop<br/>Integración de trabajo"]
    Work["feature/*, fix/*, chore/*<br/>Siempre creadas desde develop"]
    PR["PR de trabajo<br/>Checks por commit e integración"]
    DevGate{"Candidato DEV aprobado<br/>Tests, cobertura, seguridad<br/>y 3 Quality Gates Sonar"}
    Freeze["Congelar nuevos merges funcionales<br/>Fijar SHA y manifiesto"]
    Release["release / QA<br/>Mismos digests y aceptación"]
    QAGate{"Aceptación QA aprobada"}
    Main["main / PROD<br/>Versión estable y tag inmutable"]
    Fix["Corrección desde develop<br/>fix/*"]

    MainBase -->|"Crear rama inicialmente"| Develop
    MainBase -.->|"Crear rama release desde la misma base"| Release
    Develop -->|"Crear rama de trabajo"| Work
    Work --> PR
    PR -->|"Merge commit"| Develop
    Develop --> DevGate
    DevGate -->|"Sí"| Freeze
    Freeze -->|"PR develop a release"| Release
    Release --> QAGate
    QAGate -->|"Sí: PR release a main"| Main
    QAGate -->|"Defecto"| Fix
    DevGate -->|"No: corregir, sin promoción"| Fix
    Fix --> PR
```

Durante la estabilización se permite trabajar en nuevas ramas, pero no integrar funcionalidades nuevas a `develop`. Las correcciones siguen el mismo flujo; no se editan `release` o `main` directamente.

Los commits de merge tendrán SHA distintos. Se compara el contenido y el manifiesto aprobado, y se promueven los mismos digests de imagen. Si el candidato cambia, la aprobación anterior deja de habilitar la promoción. Los merges no ejecutan `terraform apply` automáticamente.

## 7. Escaneo de cada commit y Sonar en develop

```mermaid
flowchart TB
    Stage["Cambios preparados en el índice Git"]
    Snapshot["Hook pre-commit<br/>Snapshot exacto de staged"]
    LocalStatic["PMD Java 25 + Gitleaks<br/>Checkov y validación IaC cuando apliquen"]
    LocalGate{"Checks locales aprobados"}
    Commit["Crear commit"]
    Push["Push o PR"]
    Enumerate["Enumerar todos los SHA nuevos<br/>Incluye intermedios y merge commits"]
    CIStatic["Analizar cada snapshot completo<br/>Política controlada y herramientas fijadas"]
    Evidence["Evidencia por SHA<br/>Versión de herramientas + hash de políticas"]
    Integration["Build y resultado de integración<br/>Tests, JaCoCo, arquitectura y seguridad"]
    Merge["Integrar en develop"]
    Exact["Checkout del SHA exacto de develop<br/>Runner local dedicado y confiable"]
    Sonar["SonarQube Community local<br/>Tres proyectos independientes<br/>Solo snapshots de develop"]
    SonarGate{"Tres Quality Gates verdes<br/>Mismo candidato y analysisId verificados"}
    Manifest["Manifiesto DEV aprobado<br/>Digests, SHA, reportes y políticas"]
    Promotion["Habilitar PR develop a release"]
    Block["Bloquear creación local,<br/>integración o promoción correspondiente"]

    Stage --> Snapshot --> LocalStatic --> LocalGate
    LocalGate -->|"Sí"| Commit --> Push --> Enumerate --> CIStatic --> Evidence --> Integration
    LocalGate -->|"No"| Block
    CIStatic -->|"Fallo o SHA sin evidencia"| Block
    Integration -->|"Checks aprobados"| Merge --> Exact --> Sonar --> SonarGate
    Integration -->|"Fallo"| Block
    SonarGate -->|"Sí"| Manifest --> Promotion
    SonarGate -->|"Fallo, timeout o evidencia ausente"| Block
```

El hook ayuda, pero CI es la autoridad: saltarse el hook no permite integrar código sin validar. Un push con cinco commits produce evidencia de los cinco; no basta analizar `HEAD`. La creación de ramas, el historial introducido y los commits previamente comprobados se resuelven explícitamente en el enumerador. Las ejecuciones no se cancelan dejando SHA sin comprobar.

Sonar Community no se presenta como análisis nativo de PR o multirrama. Los tres proyectos reciben exclusivamente `develop`, sin mezclar análisis de ramas de trabajo, `release` o `main`. Los análisis se serializan por proyecto y esperan el resultado concreto, no el último resultado disponible.

El gate de JaCoCo mantiene al menos 90 % de líneas y ramas unitarias por servicio, aunque Sonar esté verde. Un job no puede cambiar su política y autoaprobarse: los cambios en reglas, exclusiones y workflows se revisan frente a la política controlada. Los jobs sobre código no confiable no reciben `SONAR_TOKEN` ni credenciales AWS.

## 8. AWS privado y aislamiento por ambiente

```mermaid
flowchart TB
    User["Cliente autenticado"]
    GitHub["GitHub Actions<br/>Plan y despliegue explícitos<br/>OIDC limitado al ambiente"]

    subgraph Account["Cuenta AWS del ambiente: DEV, QA o PROD"]
        Cognito["Cognito<br/>Identidad del ambiente"]
        APIGW["API Gateway HTTP API<br/>JWT, scopes y rutas públicas"]
        Role["Roles de plan y despliegue<br/>Cuenta y ambiente esperados"]
        SQS["SQS Standard y DLQ<br/>Colas propias del ambiente"]
        Dynamo[("DynamoDB<br/>Datos separados por servicio")]
        ECR["ECR<br/>Imágenes fijadas por digest"]
        Logs["CloudWatch<br/>Logs, métricas y alarmas"]
        S3Layers["S3<br/>Capas de imágenes ECR"]
        S3State[("Bucket S3 de estado Terraform<br/>Solo roles CI y bootstrap")]

        subgraph VPC["VPC privada del ambiente: dos zonas"]
            VpcLink["VPC Link<br/>Security group de ingreso"]
            ALB["ALB interno<br/>Sin rutas públicas internal"]
            InterfaceEP["Interface endpoints<br/>SQS, ECR API, ECR DKR y Logs"]
            GatewayEP["Gateway endpoints<br/>DynamoDB y S3"]

            subgraph AZA["Zona A: subred privada"]
                ECS_A["Tareas ECS Fargate<br/>Catálogo, Órdenes e Inventario<br/>Task role propio por servicio"]
            end

            subgraph AZB["Zona B: subred privada"]
                ECS_B["Tareas ECS Fargate<br/>Réplicas según configuración IaC"]
            end

            VpcLink --> ALB
            ALB --> ECS_A
            ALB --> ECS_B
            ECS_A --> InterfaceEP
            ECS_B --> InterfaceEP
            ECS_A --> GatewayEP
            ECS_B --> GatewayEP
        end

        APIGW -->|"Integración privada"| VpcLink
        InterfaceEP --> SQS
        InterfaceEP --> ECR
        InterfaceEP --> Logs
        GatewayEP --> Dynamo
        GatewayEP --> S3Layers
    end

    User -->|"Autenticarse"| Cognito
    User -->|"HTTPS y JWT"| APIGW
    Cognito -.->|"Emisor y claves para validar JWT"| APIGW
    GitHub -->|"Credenciales temporales OIDC"| Role
    Role -->|"Acceso al estado y lock autorizados"| S3State
    Role -.->|"Aplicar IaC autorizada"| VPC
    Role -.->|"Configurar recursos del ambiente"| Dynamo
    Role -.->|"Configurar recursos del ambiente"| SQS
```

La topología se instancia por ambiente con estado, cuenta, VPC, tablas, colas, identidad, permisos y configuración independientes. PROD prevé al menos dos tareas por servicio; DEV y QA pueden comenzar con una según las variables del plan. El dibujo no implica que exista ya alta disponibilidad demostrada.

- Sin NAT Gateway ni IP pública en las tareas.
- Sin descargas de paquetes al arrancar; imágenes completas y verificadas.
- El endpoint S3 permite descargar capas de ECR; los task roles no acceden al bucket de estado Terraform, reservado a roles CI y bootstrap.
- El ALB acepta tráfico del VPC Link y los servicios restringen sus comunicaciones mediante security groups.
- La entrada sobrescribe headers internos de identidad con claims verificados.
- Solo se exponen las operaciones públicas de Inventario; las rutas internas no se publican.
- TLS externo; el cifrado y autenticación entre workloads se documentan como evolución productiva, sin afirmar cifrado extremo a extremo en la demo.
- El acceso a Secrets Manager u otros servicios requiere agregar su conectividad privada cuando se utilice.
- SonarQube y su PostgreSQL permanecen locales, fuera de las cuentas de ejecución del producto.

## Evidencias que acompañarán a los diagramas

| Diagrama | Evidencia requerida durante la implementación |
|---|---|
| Contexto | README, alcance y colección de solicitudes. |
| Contenedores | Módulos generados, permisos por propietario y contratos AsyncAPI. |
| Reserva | Test de timeout posterior al commit y recuperación desde outbox. |
| Confirmación y expiración | Carrera real contra persistencia y redelivery posterior al commit. |
| Estados | Tests de cada transición y de irreversibilidad. |
| Ramas | PR, tags y manifiestos que conserven los digests. |
| Calidad | Resultado por SHA, reportes JaCoCo y tres analysisId de Sonar. |
| AWS | Terraform validado y, solo si se despliega, evidencia real de red e IAM. |

Los resultados locales, estáticos y reales de AWS se etiquetarán por separado. Ningún diagrama sustituye pruebas ni acredita por sí solo que una garantía esté implementada.
