# Plan maestro de entrega: plataforma reactiva de ticketing

**Prueba técnica de ingreso a Nequi · planificación desde cero · 16 de noviembre de 2026**

> Estado: diseño y plan de ejecución. Este documento no acredita código implementado, pruebas ejecutadas ni infraestructura desplegada. Las versiones de herramientas indicadas fueron contrastadas con documentación pública durante la planificación; su combinación debe superar el bootstrap de compatibilidad.

## Lectura del paquete

| Documento | Para qué sirve |
|---|---|
| [Diagramas](DIAGRAMAS.md) | Contexto, servicios, compras, confirmación, estados, Git, calidad y AWS. |
| [Matriz de requisitos](MATRIZ-REQUISITOS.md) | Requisito, origen, decisión, prueba y evidencia esperada. |
| [Guía de sustentación](GUIA-SUSTENTACION.md) | Demostración, preguntas difíciles y límites que deben explicarse. |
| Este documento | Decisiones de implementación, contratos, seguridad, calidad, promoción y cronograma. |

Fuente funcional: **Prueba 2026 desarrolladores (2).pdf**, ocho páginas, suministrado por el usuario. No se consultaron proyectos ni implementaciones anteriores para diseñar esta solución. Las prácticas propuestas no se presentan como información interna de Nequi.

## 1. Objetivo y criterios de éxito

Construir una plataforma backend que gestione eventos y entradas numeradas, acepte compras de forma durable, reserve temporalmente, confirme mediante consumidores y evite sobreventa aun con concurrencia, mensajes duplicados y caídas de procesos.

La evaluación debe poder demostrar:

1. Cumplimiento funcional completo del PDF.
2. Inventario consistente mediante condiciones y transacciones reales en persistencia.
3. Java 25, Spring Boot 4 y WebFlux no bloqueante.
4. Clean Architecture generada con el scaffold oficial de Bancolombia.
5. Cobertura unitaria mínima del 90 %, respaldada por pruebas relevantes.
6. Código validado estáticamente en cada commit y Quality Gate Sonar aprobado en `develop`.
7. Flujo `feature/fix/chore -> develop -> release -> main`, con los mismos artefactos promovidos.
8. Configuración y aislamiento de DEV, QA y PROD desde Terraform.
9. Entorno local reproducible, documentación coherente y sustentación basada en evidencia.

### 1.1. Decisiones acordadas

| Tema | Decisión |
|---|---|
| Contextos | Catálogo, Órdenes e Inventario. |
| Entradas | Identificables y numeradas; 1 a 10 entradas distintas del mismo evento por orden. |
| Compra | Confirmación explícita mediante API; no existe pasarela de pagos real. |
| Aceptación | `202` significa solicitud y trabajo durables; no garantiza aún reserva. |
| Reserva | Hasta 600 segundos desde la aceptación inicial; ningún retry renueva el plazo. |
| Autoridad | Solo Inventario decide asignación y estados de entradas. |
| Mensajería | SQS Standard, entrega al menos una vez, efectos de negocio idempotentes. |
| Persistencia | DynamoDB con datos y permisos separados por propietario. |
| Sonar | Community Build local gratuito; una única rama analizada por proyecto: `develop`. |
| Calidad temprana | PMD, secretos e IaC por commit; Sonar y suite completa antes de salir de DEV. |
| Infraestructura | Docker Compose obligatorio; AWS desplegable con Terraform, sin apply automático. |
| Calendario | Hitos nominales 13, 14 y 15 de noviembre de 2026; commits con fechas reales. |

El plazo solicitado ya es anterior a la fecha de este plan. El cronograma es una organización de jornadas; no autoriza reconstruir ni falsificar una historia de desarrollo.

### 1.2. Límites de la primera entrega

Incluye eventos, entradas, reservas, órdenes, confirmación, cortesías, expiración, auditoría, recuperación, seguridad, observabilidad, IaC, CI y documentación.

Quedan fuera frontend, cobros, reembolsos, cambios de capacidad de eventos activos, cancelación de ventas, EKS, service mesh, Kafka adicional, event sourcing completo y operación multirregión. No se inventarán precios ni ingresos monetarios: el PDF no define ese modelo. Solo `SOLD` contabiliza ventas en cantidades; `COMPLIMENTARY` ocupa capacidad sin venta.

## 2. Arquitectura y propiedad

| Servicio | Datos y responsabilidad | Restricción |
|---|---|---|
| `catalog-service` | Nombre, fecha, lugar, capacidad y publicación del evento. | No modifica entradas ni calcula disponibilidad. |
| `order-service` | Comprador, solicitud original, idempotencia HTTP, confirmación y seguimiento de saga. | No decide si una entrada puede venderse. |
| `inventory-service` | Entradas, reservas, deadline, estados, contadores y auditoría. | Única autoridad sobre asignación y venta. |

No habrá lectura o escritura directa de tablas ajenas ni transacciones entre propietarios. Órdenes coordina una saga con transacciones locales y comandos durables. El costo de separar estos servicios debe explicarse: hay recuperación, consistencia eventual de proyecciones y más contratos que en un único propietario transaccional.

En la primera entrega, cada servicio puede alojar API, relay, consumidores y tareas periódicas. Separar API y worker en procesos distintos será posible mediante configuración sin crear un nuevo contexto de negocio.

### 2.1. Scaffold y versiones iniciales

| Componente | Base propuesta |
|---|---|
| Java | 25, sin características preview. |
| Spring Boot | 4.1.2. |
| Scaffold Bancolombia | 5.2.1. |
| Gradle Wrapper | 9.4.1. |
| AWS SDK | Java v2, clientes asíncronos. |
| SonarScanner Gradle | 7.5.0.8588. |
| PMD | 7.29.0 estable, análisis Java 25. |

Fijar dependencias, plugins e imágenes. No usar versiones dinámicas o `latest`. El primer cambio técnico verificará generación, compilación, pruebas, bytecode Java 25 y compatibilidad de los analizadores. No sustituir el stack obligatorio para resolver una incompatibilidad de herramientas.

Generar cada servicio con el plugin oficial, tipo reactivo, Java 25, métricas activas y sin Lombok. Añadir entry points WebFlux/SQS y adaptadores DynamoDB/SQS; Órdenes tendrá `restconsumer`. Desactivar analytics del generador. Conservar el resultado del scaffold y hacer cambios quirúrgicos, sin reescribir su estructura manualmente.

### 2.2. Capas

- `domain/model`: entidades, objetos de valor, resultados e interfaces; sin Spring ni AWS SDK.
- `domain/usecase`: reglas de aplicación y coordinación mediante puertos.
- `infrastructure/entry-points`: HTTP, consumidores y tareas periódicas.
- `infrastructure/driven-adapters`: DynamoDB, SQS y HTTP.
- `applications/app-service`: composición, configuración y arranque.

Código, nombres y comentarios en inglés. Records para DTO y valores; contratos explícitos para errores. No compartir entidades Java entre servicios; compartir especificaciones versionadas de contratos.

### 2.3. Reactividad

Usar `Mono`/`Flux`, `DynamoDbAsyncClient`, `SqsAsyncClient` y `WebClient`. Prohibir `.block()`, `.join()`, `Thread.sleep()` y clientes síncronos en rutas productivas. Administrar suscripciones en límites de ejecución, no como trabajo oculto en casos de uso. Limitar concurrencia y buffers, manejar cancelación y shutdown. No introducir Virtual Threads en este flujo de I/O asíncrono.

## 3. Contratos públicos y estados

OpenAPI y AsyncAPI serán fuentes versionadas del contrato. Prefijo HTTP `/api/v1`; fechas ISO 8601 normalizadas a UTC; identificadores opacos; paginación por cursor.

| Endpoint | Comportamiento |
|---|---|
| `POST /events` | Administrador crea evento; `202` con `PROVISIONING` y ubicación. |
| `GET /events` | Eventos publicados, paginados. |
| `GET /events/{eventId}` | Metadatos y estado de preparación. |
| `GET /events/{eventId}/tickets` | Entradas numeradas, estado observado y cursor. |
| `GET /events/{eventId}/availability` | Capacidad y cantidades por los cinco estados. |
| `POST /orders` | `eventId` y `ticketIds`; orden durable, `202` y ubicación. |
| `GET /orders/{orderId}` | Orden propia y estado autoritativo. |
| `POST /orders/{orderId}/confirm` | Confirmación durable, `202`. |
| `POST /admin/complimentary-orders` | Solicitud de cortesía autorizada para un beneficiario. |

### 3.1. Validaciones

- Nombre 1-120 caracteres; lugar 1-200; fecha futura al crear evento.
- Capacidad entre 1 y 100.000.
- Orden de 1 a 10 entradas distintas, todas del mismo evento.
- Cuerpo máximo de 16 KiB.
- Página de 50 elementos por defecto y máximo 100.
- Comprador obtenido de autenticación, nunca de un campo controlado por el cliente.
- `Idempotency-Key` en creación y confirmación.
- Autorización por propietario y rol en cada operación relevante.

Errores `application/problem+json` con código estable y `traceId`: 400 validación, 401/403 autenticación/autorización, 404 inexistencia o recurso ajeno, 409 conflicto, 410 reserva vencida, 429 límite y 503 dependencia requerida no disponible. No filtrar detalles de infraestructura ni stack traces.

### 3.2. Máquina de estados de entrada

| Origen | Destino | Condición |
|---|---|---|
| AVAILABLE | RESERVED | Todas las entradas disponibles y solicitud vigente. |
| RESERVED | PENDING_CONFIRMATION | Confirmación explícita, propia y vigente. |
| PENDING_CONFIRMATION | SOLD | Misma confirmación y plazo vigente. |
| RESERVED | AVAILABLE | Vencimiento. |
| PENDING_CONFIRMATION | AVAILABLE | Vencimiento anterior a completar venta. |
| AVAILABLE | COMPLIMENTARY | Cortesía administrativa autorizada. |

`SOLD` y `COMPLIMENTARY` son finales. No implementar transiciones inversas. Las transiciones incluirán reserva, todas sus entradas, contadores, auditoría y outbox en una transacción local.

### 3.3. Orden y ambigüedad del PDF

El documento reutiliza estados de entrada para órdenes. La respuesta distinguirá:

- `status`: uno de los cinco valores exigidos.
- `workflowStatus`: RESERVING, AWAITING_CONFIRMATION, CONFIRMING, COMPLETED, REJECTED o EXPIRED.
- `resolution`: motivo del resultado cuando exista.
- `inventoryVersion`: versión observada en la autoridad.
- `requestedTicketIds`: solicitud histórica.
- `activeTicketIds`: asignación vigente.

En una orden pendiente, rechazada o expirada, `status=AVAILABLE` significa que esa orden no tiene asignación activa. No afirma que sus entradas históricas estén disponibles para otro comprador. Esta interpretación y la semántica durable de `202` deben figurar en README, OpenAPI, ADR y sustentación.

## 4. Flujos y protocolo de recuperación

### 4.1. Crear evento

1. Catálogo persiste evento PROVISIONING y outbox en una transacción.
2. Inventario recibe preparación y crea IDs de entrada deterministas.
3. Inicializa por bloques con marcadores transaccionales de progreso.
4. Reintentos omiten bloques terminados y no sobrescriben entradas usadas.
5. Solo al completar la capacidad marca READY y publica `InventoryReady`.
6. Catálogo pasa a ACTIVE. Reserva condiciona READY aunque el catálogo se retrase.

Capacidad y numeración quedan inmutables. No se venden inventarios parciales.

### 4.2. Iniciar compra

1. Órdenes valida identidad, formato y clave idempotente.
2. Guarda orden, hash canónico, deadline y `PurchaseRequested` en una transacción local.
3. Fija `reservationId=orderId`, sin regenerarlo en retries.
4. Intenta una reserva HTTP breve, con presupuesto de 500 ms.
5. El consumidor ejecuta la misma operación idempotente: verifica disponibilidad real y recupera solicitudes interrumpidas.
6. Puede responder `202 RESERVING`; nunca afirmar reserva sin resultado autoritativo.
7. Inventario publica snapshots versionados del resultado.

Un timeout no demuestra fracaso y no dispara cancelación especulativa. Un rechazo de disponibilidad también se persiste de forma terminal. Si compite con otro intento del mismo ID, una condición de inexistencia decide qué resultado prevalece; un rechazo no puede coexistir con una reserva exitosa para ese ID.

### 4.3. Confirmar

1. Órdenes verifica propietario y estado autoritativo.
2. Sin reserva preparada devuelve `409 ORDER_NOT_READY`; si venció, `410`.
3. Registra intención y `ConfirmPurchase` en una transacción local.
4. `commandId` de confirmación estable por orden, aunque cambie la clave HTTP.
5. Inventario pasa a PENDING_CONFIRMATION con auditoría y evento.
6. Completa SOLD mediante otra transacción que vuelve a comprobar deadline y propiedad.
7. Una caída intermedia se reanuda con el mismo comando.
8. Si venció antes de completar, libera la reserva. No existe pago externo que reembolsar.

### 4.4. Consultar y reconciliar

Órdenes valida propietario antes de consultar Inventario. La lectura autoritativa usa consistencia apropiada en la tabla base. Si Inventario está caído, responder 503, sin presentar la proyección local como estado actual.

La proyección solo aplica versiones crecientes. Un reconciliador busca órdenes estancadas mediante índice por próxima revisión, consulta la autoridad y reintenta la misma operación, sin nuevos IDs. Una inconsistencia imposible, como un evento para una orden inexistente, se registra y se pone en cuarentena; no se inventa una orden.

### 4.5. Cortesías

Órdenes registra solicitud administrativa idempotente. Inventario realiza AVAILABLE -> COMPLIMENTARY de forma atómica, conservando beneficiario, actor y motivo. Reduce disponibilidad y no suma ventas. Usa la misma exclusión de asientos, auditoría y recuperación que las compras.

### 4.6. Expiración

- Deadline calculado una vez al aceptar la solicitud, máximo 600 segundos.
- `now >= expiresAt` implica vencimiento; revisar el reloj en cada intento y retry.
- PENDING_CONFIRMATION conserva el mismo plazo.
- Reloj UTC inyectable; sincronización de reloj documentada para operación distribuida.
- Worker cada segundo con concurrencia limitada y reclamación condicional de trabajo.
- Liberar todas las entradas de una reserva en una transacción.
- Antes de reasignar entradas vencidas, liberar la reserva anterior completa; nunca parcialmente.
- Objetivo de materialización de liberación: hasta cinco segundos en condiciones normales.
- La disponibilidad puede ser conservadora durante ese retraso. Si excede el límite, indicar indisponibilidad temporal en lugar de presentar cifras como actualizadas.
- El vencimiento lógico no depende de que el worker esté vivo; ninguna confirmación tardía es válida.

TTL solo limpia registros auxiliares. No es el mecanismo de expiración de negocio. No se prometerá puntualidad absoluta de un proceso periódico ante fallos.

## 5. Modelo de datos y concurrencia

Una tabla lógica por servicio. Prefijos separan entidades y registros operativos. Una transacción solo toca datos del mismo propietario.

| Acceso | Implementación |
|---|---|
| Evento por ID | Get consistente por clave. |
| Listado de eventos publicados | Índice por estado y fecha; eventualidad explícita. |
| Orden por ID | Get por clave y validación de propietario. |
| Reserva por orden | Lectura consistente por reservationId. |
| Entradas | Query paginada por evento y partición. |
| Disponibilidad | TransactGetItems de dieciséis contadores. |
| Vencimientos | Registros operativos por partición y deadline. |
| Outbox | Query de pendientes, lease y próximo intento. |
| Reconciliación | Índice por próxima revisión. |

### 5.1. Particionado de Inventario

Dieciséis particiones fijas por evento, determinadas por número de entrada. Cada partición mantiene cantidades por estado. Actualizar los asientos y agrupar los deltas por partición para no escribir dos veces el mismo contador dentro de una transacción.

Una orden de diez entradas modifica hasta diez entradas y diez contadores, además de reserva, auditoría y registros auxiliares. Verificar que todas las operaciones respeten 100 acciones, 4 MB y tamaño máximo de ítem. No añadir un contador global escrito por cada compra.

La preparación masiva utiliza bloques con marcadores y condiciones; no tratar BatchWriteItem como transacción indivisible.

### 5.2. Invariantes

```text
capacity = available + reserved + pendingConfirmation + sold + complimentary
```

Cada entrada tiene exactamente un estado y como máximo una asignación activa. No hay cantidades negativas, reservas parciales ni transiciones de salida de estados finales. Cada transición aplicada tiene auditoría durable.

Las condiciones incluyen estado esperado, versión y reservationId. Una liberación antigua no puede afectar a un nuevo propietario. Una carrera entre confirmación y expiración tiene un único ganador; el perdedor relee el resultado.

Los dieciséis contadores se leen mediante un snapshot transaccional; dieciséis lecturas independientes no equivalen a ese snapshot. El sharding reduce contención, pero no demuestra capacidad ilimitada: publicar mediciones reales.

## 6. SQS, outbox e idempotencia

Tres colas Standard: `inventory-commands`, `order-events` y `catalog-events`; cada una con DLQ.

| Parámetro inicial | Valor |
|---|---|
| Long polling | 20 segundos. |
| Mensajes por recepción | Hasta 10. |
| Visibilidad | 60 segundos; extensión para trabajo largo. |
| maxReceiveCount | 5. |
| Retención principal | 4 días. |
| Retención DLQ | 14 días. |
| Concurrencia inicial por consumidor | 16. |

Mensajes con `messageId`, tipo, versión de esquema, agregado y versión, correlationId, causationId, fecha y payload. No incluir tokens de autenticación. Los eventos de estado son snapshots completos, no deltas ciegos.

### 6.1. Outbox

Todo productor guarda cambio de negocio e intención de publicación en la misma transacción. Relay con lease recuperable, envío rápido tras commit y barrido de recuperación. Objetivo normal de publicación p95 <=1 segundo. El hecho de guardar outbox no prueba que SQS haya recibido el mensaje.

Publicación repetida es válida. Borrar/reconocer mensaje consumido solo después del resultado durable. Mantener marcador inbox y efecto de negocio atómicos cuando corresponda. Para confirmaciones de dos pasos, el marcador terminal no debe impedir reanudar PENDING_CONFIRMATION.

### 6.2. Idempotencia

- HTTP: usuario + operación + Idempotency-Key y hash canónico.
- Misma clave y contenido devuelve misma operación; contenido diferente produce 409.
- Inbox por identificador de aplicación, no por MessageId de transporte.
- Registros auxiliares de deduplicación: treinta días.
- IDs de orden/reserva nunca se reutilizan; sus estados terminales siguen protegiendo el negocio.
- Máximo tres intentos transitorios con backoff y jitter; un solo nivel administra el presupuesto para evitar multiplicación de retries.
- Rechazos de negocio se registran y reconocen; no se reintentan como fallos transitorios.

### 6.3. Recuperación esperada

| Corte de ejecución | Recuperación |
|---|---|
| Antes de guardar orden | No se inicia reserva. |
| Después de guardar intención | Outbox/consumidor continúan. |
| Reserva aplicada, respuesta HTTP perdida | Mismo ID recupera el resultado. |
| Publicado, pero sin marcar envío | Duplicado sin segundo efecto. |
| Commit de negocio, sin ACK | Redelivery inocua. |
| PENDING_CONFIRMATION y caída | Reanudar mismo comando o expirar. |
| Mensaje antiguo después del terminal | Ignorar por versión/estado. |
| SQS inaccesible | Trabajo durable pendiente; deadlines no se extienden. |
| Orden abandonada | Inventario libera al vencer. |
| Mensaje incompatible | DLQ, evidencia y alarma. |

## 7. Branching y promoción

### 7.1. Ramas

`main` es la base inicial. Crear `develop` y `release` desde el mismo bootstrap de `main`.

```text
feature/*, fix/*, chore/*
  -> PR develop
  -> validación completa DEV y Sonar
  -> PR release
  -> aceptación QA
  -> PR main
  -> tag estable / publicación autorizada
```

Ramas de trabajo siempre desde develop. PR obligatoria para ramas permanentes. Merge commits conservando historia incremental. Sin force push, borrado de ramas permanentes, commits directos ni squash entre ellas. Validar origen, destino y repositorio autorizado. Conventional Commits y revisión de diff/evidencia. La prueba individual no dependerá de conseguir un segundo desarrollador para aprobar PR; habrá revisión automatizada obligatoria y humana adicional cuando exista colaborador.

### 7.2. Congelación

Seleccionar SHA verde de develop y congelar nuevos merges funcionales. Promover a release, aceptar y corregir siguiendo `fix -> develop -> release`. Nuevas funcionalidades pueden avanzar en sus ramas sin integrarse durante estabilización. Después de release -> main, levantar congelación. No introducir cherry-picks o cambios directos como excepción oculta.

### 7.3. Construir una vez

Imágenes construidas sobre candidato aprobado de develop. Promover los mismos digests a QA y PROD; la configuración de ambiente es externa al binario. Un merge cambia SHA pero no debe cambiar el contenido aprobado.

Manifiesto inmutable de candidato: SHA fuente, hash del contenido, tres digests, versiones de contratos/IaC, SBOM, pruebas, análisis estáticos, política aplicada y tres analysisId/Quality Gates Sonar. Verificar igualdad de contenido y artefactos; una modificación invalida la aprobación y vuelve a DEV.

Tags RC `v1.0.0-rc.N` y estable `v1.0.0`, inmutables. Las correcciones producen nuevas versiones.

## 8. Calidad en cada commit

### 8.1. Controles estáticos

| Control | Herramienta |
|---|---|
| Errores probables, recursos, complejidad y malas prácticas Java | PMD 7.29.0, Java 25 y auxClasspath correcto. |
| Secretos | Gitleaks con resultados redactados. |
| IaC y configuraciones compatibles | Checkov. |
| Terraform | fmt -check, validate y TFLint. |
| Dependencias e imágenes | Trivy y SBOM. |
| Dependencias entre capas | ArchUnit en el build de verificación. |

Reglas y severidades versionadas. Fallar ante incumplimientos de las reglas activas. Las exclusiones deben ser específicas, justificadas y revisables; no excluir adaptadores ni código difícil para mejorar números.

### 8.2. Hook local

Pre-commit versionado que analiza un snapshot exacto del índice de Git. No mezclar cambios sin staged, no alterar el working tree ni formatear automáticamente. Ejecutar análisis de Java, secretos y configuración aplicable. Registrar hash del árbol y política. Si una categoría no aplica, registrarlo explícitamente.

### 8.3. CI por SHA

CI es la autoridad, porque los hooks pueden omitirse. Enumerar todos los commits introducidos, incluidos intermedios y merge commits; un push de cinco commits requiere evidencia para cinco SHA. Analizar también el resultado de integración.

Resolver creación de rama con `before` vacío mediante el historial alcanzable y la base de integración, evitando omitir commits no verificados. Para historia divergente, calcular el conjunto introducido; no confiar solo en la punta. No cancelar jobs dejando huecos de cobertura. Cachear únicamente resultados de SHA + versión de herramientas + hash de política idénticos.

Un commit no podrá desactivar su propia validación: cambios de reglas, exclusiones, scripts y workflows serán comparados con la política confiable vigente. No aceptar un resultado emitido por configuración degradada sin revisión explícita.

Jobs sobre código no confiable en runners aislados y sin secretos Sonar/AWS. Nunca usar el runner local de Sonar para PR de forks.

## 9. Sonar en develop, antes de release

### 9.1. Instalación local

Perfil Docker `quality` con SonarQube Community Build y PostgreSQL dedicado, persistencia y acceso local. No es base de datos de los microservicios ni servicio AWS. Configuración reproducible del gate y permisos; no reiniciar historiales para ocultar hallazgos.

Tres proyectos independientes: `ticketing-catalog`, `ticketing-orders`, `ticketing-inventory`. Cada proyecto abarca todos los módulos Gradle de su servicio y recibe únicamente snapshots de develop. Community no ofrece aquí análisis nativo multirrama ni de PR. No configurar sonar.branch.name o parámetros de PR, ni mezclar candidatos de otras ramas en el historial.

Runner local dedicado para código confiable integrado en develop. Checkout del SHA exacto del workflow, no de una punta cambiante. Serializar análisis por proyecto. El runner de calidad no dispone de credenciales de despliegue AWS.

### 9.2. Integración Gradle

Plugin org.sonarqube 7.5.0.8588. Ejecutar compilación, pruebas y XML JaCoCo antes del scanner; conectar rutas reales del scaffold. Comprobar bytecode, número de fuentes y cobertura importada. Un scanner exitoso no prueba por sí solo importación correcta.

### 9.3. Gate

| Métrica | Condición de aprobación |
|---|---|
| JaCoCo unitario, líneas | >=90 % global por servicio. |
| JaCoCo unitario, ramas | >=90 % global por servicio. |
| Cobertura Sonar importada | >=90 % global y en código nuevo aplicable. |
| Duplicación | <=3 %. |
| Confiabilidad, seguridad y mantenibilidad | Rating A. |
| Bugs y vulnerabilidades detectados pendientes | Cero. |
| Security Hotspots | 100 % revisados con resolución documentada. |

Mantener controles globales desde el primer análisis. No convertir defectos propios en legado para aprobar. Revisar falsos positivos y hotspots individualmente. Sonar no sustituye JaCoCo, pruebas de concurrencia, PIT ni scanners de infraestructura/dependencias.

Esperar resultado concreto con `sonar.qualitygate.wait=true` y timeout inicial de 600 segundos. Capturar ceTaskId/analysisId, SHA, métricas y gate. Verificar los tres servicios para el mismo candidato. No consultar solamente el último resultado del proyecto.

Gate rojo, cobertura ausente, timeout, scanner fallido, Sonar caído o SHA diferente bloquean develop -> release. SONAR_TOKEN se almacena como secreto de mínimo alcance y nunca se imprime, versiona ni publica como artifact.

### 9.4. DEV aprobado

Solo se aprueba un candidato con análisis de todos sus commits, build, cobertura, pruebas, arquitectura, concurrencia, recuperación, tres Sonar verdes, seguridad/IaC, smoke de contenedores y manifiesto exacto.

Si posteriormente se habilita AWS, añadir smoke real de DEV. Mientras solo exista ejecución local, documentar esa frontera. Sonar valida código; no demuestra despliegue ni corrección funcional por sí solo.

## 10. Ambientes e infraestructura como código

### 10.1. Organización

```text
infra/
  bootstrap/
  modules/
    network/
    data/
    messaging/
    identity-ingress/
    service/
    observability/
  envs/
    dev/
    qa/
    prod/
```

Mismos módulos en todos los ambientes, raíces pequeñas y valores tipados. Cuenta AWS por ambiente en el diseño objetivo; account ID y rol esperado son entradas obligatorias antes de aplicar. Guardas contra cuenta incorrecta. Estados, VPC, IAM, tablas, colas, identidad y logs separados. No copiar datos productivos a ambientes inferiores.

| Parámetro inicial | DEV | QA | PROD |
|---|---:|---:|---:|
| Rama | develop | release | main |
| Tareas mínimas por servicio | 1 | 1 | 2 |
| Tareas máximas por servicio | 2 | 3 | 6 |
| CPU por tarea | 0,5 vCPU | 0,5 vCPU | 0,5 vCPU |
| Memoria por tarea | 1 GiB | 1 GiB | 1 GiB |
| Logs | 7 días | 14 días | 30 días |
| Recuperación puntual DynamoDB | No | Sí | Sí |
| Protección de eliminación | No | No | Sí |

Los tamaños se ajustan con mediciones. Reglas de negocio, estados y deadlines no se relajan por ambiente.

### 10.2. Variables

IaC genera APP_ENV, perfil aws, región, nombres de tablas, URL/ARN de colas, direcciones internas, límites y observabilidad desde los recursos. Sin endpoints hardcodeados ni .env productivo manual.

`.env.example` contiene nombres y ejemplos no sensibles; archivos locales generados quedan fuera de Git. Terraform administra referencias y permisos de secretos, no valores en texto plano. Cargar valores por procedimiento separado y usar Secrets Manager o parámetros protegidos. Ningún secreto en tfvars, outputs, logs, imágenes o artifacts.

Backend S3 cifrado, versionado, sin acceso público y con use_lockfile. Estados separados y versiones de Terraform/providers fijadas con lockfile. Migraciones compatibles y reanudables para tablas e índices.

### 10.3. AWS

Región inicial us-east-1. VPC de dos zonas, Fargate privado sin IP pública, API Gateway HTTP API con JWT/Cognito, VPC Link y ALB interno. DynamoDB, SQS, ECR y CloudWatch. Sin NAT Gateway.

Endpoints gateway DynamoDB/S3; endpoints interface SQS, ECR API, ECR DKR y Logs. Crear otros únicamente si una dependencia realmente los usa. Construir imágenes antes del despliegue; ninguna descarga de paquetes al arrancar.

API Gateway valida JWT y sobrescribe identidad interna con claims verificados. ALB acepta solo VPC Link; tareas aceptan ALB y servicios expresamente permitidos. Sin rutas públicas /internal. La autorización por propietario y rol permanece en aplicación. Tokens nunca viajan en mensajes SQS.

Límite explícito: TLS externo y aislamiento privado interno en el perfil demostrativo. TLS y autenticación entre workloads se describen como evolución productiva, con costos y operación; no afirmar cifrado extremo a extremo.

### 10.4. Workflows y rollback

Workflows separados de CI, candidato, promoción, plan, deploy y rollback. Ambiente derivado de rama, no seleccionable libremente para saltar controles. OIDC con permisos mínimos y subject real del repositorio/environment. Restricción de rama en environment. Roles de plan y apply separados. Concurrencia máxima de un despliegue por ambiente.

Merge no dispara apply automático dentro del alcance acordado. Planes autenticados se tratan como sensibles. Desplegar solo cuando haya cuenta, ventana y presupuesto definidos.

Rollback a digest anterior aprobado y configuración compatible, registrando evidencia. Corrección permanente por fix -> develop -> release -> main. Expand/contract para datos. No restaurar inventario viejo ni deshacer SOLD/COMPLIMENTARY como rollback de aplicación.

## 11. Docker, seguridad y observabilidad

Compose incluye tres servicios, DynamoDB Local persistente, ElasticMQ persistente con colas/DLQ, bootstrap idempotente, identidades locales de prueba y healthchecks. Arranque: `docker compose up --build --wait`. Fixtures mediante comando explícito; reiniciar no altera inventario.

Perfil observability con OpenTelemetry Collector, Prometheus, Grafana y backend local de trazas. Perfil quality independiente para Sonar. ElasticMQ es una alternativa SQS-compatible local; no equivale a validar IAM, límites, latencias y comportamiento de AWS real.

Seguridad: task role por servicio separado de execution role; mínimo acceso a tablas/índices/colas; sin access keys permanentes; cifrado; CORS cerrado; límites de recursos; contenedores no root, capacidades mínimas y filesystem de solo lectura cuando sea compatible; Actuator restringido.

Logs JSON con traceId, orderId, reservationId, messageId y operación. Sin tokens, secretos o cuerpos completos. Métricas de latencia, conflictos, duplicados, outbox, cola, DLQ, vencimientos, confirmación y reconciliación.

Alarmas iniciales: DLQ >0; outbox >30 s; liberación >5 s; mensaje principal >60 s; HTTP 5xx >5 % durante cinco minutos. Son objetivos/configuración inicial, no resultados demostrados.

Runbooks para cola caída, consumidor detenido, replay, reservas atrasadas, restauración, despliegue fallido y desmontaje. Replay conserva IDs originales. Presupuesto y costos por ambiente/duración; Fargate, ALB y endpoints no se describen como gratuitos. Limitar autoscaling y retención; limpiar imágenes antiguas y verificar recursos residuales al desmontar.

## 12. Estrategia de pruebas y evidencia

JUnit Jupiter, Mockito, reactor-test, StepVerifier, WebTestClient, Testcontainers, JaCoCo, ArchUnit y PIT. Clock inyectable y tiempo virtual para pruebas temporales. Unitarios con repositorios simulados e integración real con motores locales; mocks no prueban atomicidad.

Cobertura unitaria >=90 % de líneas y ramas por servicio. Integración reportada por separado. PIT en dominio/casos de uso con objetivo >=80 % de mutaciones eliminadas.

### 12.1. Casos obligatorios

| Grupo | Escenarios |
|---|---|
| Eventos | Validación, provisioning parcial, reanudación, prohibición de reserva antes de READY. |
| Compra | Una y diez entradas, IDs repetidos, evento incorrecto, entradas ocupadas. |
| Atomicidad | Fallo de una entrada impide toda la reserva múltiple. |
| Concurrencia | Cien compradores por una entrada; mil intentos sobre cien entradas. |
| Idempotencia | Reintentos simultáneos, payload diferente, aislamiento por usuario. |
| Estados | Confirmación repetida, finales irreversibles, evento antiguo ignorado. |
| Tiempo | Antes, igual y después del deadline; Clock fijo. |
| Carreras | Confirmación/expiración, cortesía/compra, varios expiradores. |
| Fallos | Cortes antes/después de commit, envío, marcado de outbox y ACK. |
| Mensajes | Duplicados, desorden, incompatibles, DLQ y redrive. |
| Persistencia | Reinicio completo sin perder propiedad ni órdenes. |
| Seguridad | Orden ajena, rol incorrecto, token inválido, identidad falsificada y abuso. |
| Reactividad | Sin bloqueo, cancelación, concurrencia y memoria acotadas. |
| Contabilidad | Solo SOLD suma ventas; COMPLIMENTARY consume capacidad. |

Generar secuencias de reservar, confirmar, expirar y redeliver, comprobando invariantes después de cada secuencia.

### 12.2. Calidad y promoción

Probar push multicommit y merge commits; hook omitido; configuración que intenta desactivar reglas; reporte de categoría no aplicable; timeout y caída de Sonar; cobertura ausente; un solo servicio Sonar rojo; resultado de SHA antiguo; artefacto cambiado; rama y account ID incorrectos; aislamiento de secretos y estados; rollback sin modificar ventas.

### 12.3. Rendimiento

k6 con dataset, semilla, hardware, recursos y duración registrados. Escenarios de lectura, compras distribuidas, últimas entradas, carga mixta, backlog/recuperación y sostenido de treinta minutos.

Metas iniciales: mil usuarios concurrentes, cero sobreventa, p95 aceptación <500 ms, p95 disponibilidad <300 ms, p95 confirmación <5 s con dependencias sanas, errores técnicos inesperados <1 % y memoria estable. Publicar resultados reales, separando rechazo por inventario de falla técnica. No afirmar capacidad cloud a partir de emuladores.

### 12.4. Reportes

Reportar SHA, herramientas/política, entorno, comandos, fecha, resultado y ubicación de artifacts. Distinguir unitario, integración local, análisis estático y ejecución AWS. No presentar capturas de un gate antiguo como validación del candidato actual.

## 13. Documentación como entregable obligatorio

| Documento futuro del repositorio | Contenido mínimo |
|---|---|
| README | Objetivo, stack, arranque limpio, configuración, ejemplos, errores, pruebas y navegación. |
| Arquitectura | Contextos, propietarios, dependencias, consistencia y decisiones. |
| Modelo de dominio | Estados, invariantes, deadlines, cortesías y dualidad orden/entrada. |
| OpenAPI | Todos los endpoints, identidad, idempotencia, validaciones y ejemplos de errores. |
| AsyncAPI | Colas, comandos/eventos, versiones, payloads, retries, deduplicación y DLQ. |
| Modelo DynamoDB | Claves, índices, acceso, límites, transacciones y sharding. |
| Desarrollo | Herramientas fijadas, scaffold, comandos, fixtures, hooks y convenciones. |
| Calidad | PMD, JaCoCo, Sonar, PIT, reglas, gates y falsos positivos. |
| Branching | Orígenes/destinos, congelación, promociones, manifiestos y tags. |
| Ambientes | Variables, cuentas, roles, backends, secretos y diferencias DEV/QA/PROD. |
| Despliegue | Bootstrap, plan, apply autorizado, smoke, rollback y desmontaje. |
| Operación | Métricas, alarmas, dashboards, logs y runbooks. |
| Seguridad | Amenazas relevantes, controles, límites y matriz de permisos. |
| Rendimiento | Dataset, metodología, resultados y cuellos de botella reales. |
| Evidencias | Matriz de requisitos y artifacts identificados por candidato. |
| Sustentación | Guion, demostración, preguntas difíciles y limitaciones. |

ADRs obligatorios: tres microservicios y costo de saga; autoridad de Inventario; semántica 202; estados de orden/entrada; SQS Standard; outbox/inbox; expiración sin TTL; sharding; AWS privado sin NAT; Sonar Community solo develop; análisis por commit; promoción por digest; variables desde IaC; fronteras de la demostración.

Diagramas editables en Mermaid junto con explicación y fecha de actualización. Documentar C4 conceptual de contexto/contenedores, secuencias de compra y confirmación, estados, Git, CI y despliegue AWS. Los diagramas deberán cambiar en el mismo PR que altere su comportamiento.

Colección ejecutable: evento, provisioning, reserva, consulta, confirmación, expiración, cortesía, idempotencia, autorización y errores. Cada ejemplo usa variables del entorno y no credenciales versionadas.

## 14. Cronograma incremental

Tres frentes paralelizables: dominio/servicios; plataforma/calidad; pruebas/documentación. Fijar contratos e invariantes antes de repartir implementación. Cada rama nace de develop y cada commit conserva evidencia.

### Jornada 1: viernes 13 (hito nominal)

1. `docs: define requirements and architecture decisions`
2. `chore: initialize branching and promotion policies`
3. `chore: generate services with Bancolombia scaffold`
4. `ci: add per-commit static analysis and staged hooks`
5. `ci: add unit coverage and architecture gates`
6. `build: add local SonarQube quality profile`
7. `ci: enforce develop Sonar quality gates`
8. `feat: define API and messaging contracts`
9. `build: add local infrastructure and bootstrap`
10. `feat(catalog): add durable event provisioning`
11. `feat(inventory): provision numbered tickets`
12. `feat(inventory): implement atomic reservations`
13. `test(inventory): verify concurrent reservations`

Salida: controles desde el inicio y evento -> inventario -> reserva funcionando.

### Jornada 2: sábado 14 (hito nominal)

1. `feat(orders): accept durable idempotent purchases`
2. `feat(messaging): add outbox and idempotent consumers`
3. `feat(orders): reconcile authoritative inventory state`
4. `feat(inventory): process explicit confirmations`
5. `feat(inventory): expire reservations safely`
6. `feat(orders): support complimentary tickets`
7. `feat(security): enforce ownership and request limits`
8. `test: cover duplicate delivery and failure recovery`
9. `feat(observability): add correlated telemetry`
10. `infra: define isolated dev qa and prod environments`

Salida: flujos completos, recuperación demostrada y calidad aprobada en develop.

### Jornada 3: domingo 15 (hito nominal)

1. `infra: add private networking and ECS services`
2. `ci: add environment-bound OIDC and promotion manifests`
3. `ci: enforce IaC and supply chain checks`
4. `test: verify per-commit and promotion quality controls`
5. `test: add end-to-end acceptance collection`
6. `perf: add load and recovery evidence`
7. `docs: add diagrams ADRs and operational runbooks`
8. `docs: add release walkthrough and evidence index`

Congelar funcionalidades, comprobar todos los gates DEV, promover develop -> release, aceptar QA, corregir por flujo completo, promover release -> main, etiquetar y levantar congelación. Añadir commits de corrección cuando la evidencia lo justifique; no fabricar fragmentación ni fechas.

## 15. Cierre y sustentación

Entregar repo, historial/PR, hooks, Sonar reproducible, reportes por SHA, Compose, contratos, colección, pruebas, Terraform por ambiente, manifiestos/digests/SBOM, diagramas, ADR, runbooks, costos y trazabilidad.

La entrega termina cuando los requisitos tienen evidencia, no hay sobreventa ni asignaciones parciales en pruebas, todos los commits fueron analizados, los tres Sonar de develop están verdes, cobertura unitaria >=90 %, clon limpio arranca, IaC/seguridad pasan, artefactos aprobados se conservan y documentación coincide con código.

La demostración seguirá la guía adjunta: arquitectura, compra, contención, duplicados, vencimiento, recuperación, cortesía, Git/artefactos, IaC/ambientes y calidad. Explicar con honestidad qué se comprobó localmente, qué se validó estáticamente y qué queda por verificar en AWS.

## 16. Fuentes técnicas consultadas

- [Scaffold Bancolombia: inicio y build](https://bancolombia.github.io/scaffold-clean-architecture/docs/getting-started).
- [Generación de proyecto](https://bancolombia.github.io/scaffold-clean-architecture/docs/tasks/generate-project), [adaptadores](https://bancolombia.github.io/scaffold-clean-architecture/docs/tasks/generate-driven-adapter) y [entry points](https://bancolombia.github.io/scaffold-clean-architecture/docs/tasks/generate-entry-point).
- [Requisitos de Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html).
- [Transacciones DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html) y [TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html).
- [Entrega al menos una vez en SQS](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html).
- [Endpoints de Fargate](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/vpc-endpoints.html) y [JWT en API Gateway](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html).
- [Backend S3 de Terraform](https://developer.hashicorp.com/terraform/language/backend/s3).
- [OIDC GitHub/AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws).
- [Checkov sobre Terraform Plan](https://www.checkov.io/7.Scan%20Examples/Terraform%20Plan%20Scanning.html).
- [ElasticMQ](https://github.com/softwaremill/elasticmq).
- [Capacidades de Sonar Community](https://docs.sonarsource.com/sonarqube-community-build/feature-comparison-table), [scanner Gradle](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/scanners/sonarscanner-for-gradle), [Java](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/languages/java) y [espera del Quality Gate](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/analysis-parameters/parameters-not-settable-in-ui).
- [PMD Java](https://pmd.github.io/pmd/pmd_languages_java.html) y [PMD 7.29.0](https://github.com/pmd/pmd/releases/tag/pmd_releases%2F7.29.0).

Las fuentes sustentan capacidades técnicas; no constituyen evidencia de que esta implementación exista o de que sus pruebas hayan pasado.
