# Registro de decisiones

Decisiones vigentes al 2026-11-16. El [modelo de ejecución](architecture-runtime.md), los [contratos](contracts.md) y la [evidencia](evidence/requirements.md) detallan su implementación. Una decisión aceptada no constituye una prueba aprobada.

## ADR-001 — Tres contextos y un propietario del inventario

**Contexto.** Crear eventos, recibir solicitudes y asignar entradas tienen responsabilidades y ritmos distintos. La asignación debe conservar invariantes incluso con réplicas y entregas duplicadas.

**Decisión.** Catalog, Orders e Inventory son microservicios con tablas propias. Solo Inventory cambia entradas, reservas, contadores y auditoría mediante transacciones condicionales. Orders mantiene una proyección versionada. Cada imagen ejecuta API o worker de forma independiente; el worker es un rol desplegable del contexto, no otra autoridad de negocio.

**Consecuencias.** Hay seis despliegues y tres imágenes, con costo operativo mayor que un monolito. La consistencia entre servicios es eventual y requiere outbox/reconciliación. Se descarta compartir tablas entre contextos o depender de un lock en memoria. No se incorpora EKS/service mesh: ECS Fargate mantiene el runtime Java/WebFlux del scaffold y permite separar consumidores de APIs. Los emuladores no prueban el escalado de ECS.

## ADR-002 — Aceptación durable y recuperación de doble escritura

**Contexto.** Guardar una orden y enviar un mensaje son operaciones en sistemas diferentes. Una caída entre ambas puede perder trabajo o duplicarlo.

**Decisión.** Guardar orden e intención outbox atómicamente antes de responder 202. El relay publica, y solo después marca el envío. IDs estables y condiciones hacen inocuo repetir el efecto. Orders puede intentar reservar por HTTP durante 500 ms; el mensaje durable sigue siendo la recuperación.

**Consecuencias.** 202 confirma recepción durable, no reserva ni venta. La interpretación del requisito de “encolado inmediato” incluye persistencia en outbox: no se garantiza presencia física inmediata en SQS. El backoff del relay puede retrasar su publicación. Se descarta responder éxito antes de persistir y se descarta una transacción distribuida DynamoDB/SQS inexistente. El consumidor reconoce un mensaje después del resultado durable.

## ADR-003 — Deadline inmutable y expiración por mensaje

**Contexto.** Una reserva dura como máximo diez minutos. Consultar dieciséis particiones cada segundo por réplica multiplica consultas vacías. TTL no ofrece puntualidad de negocio.

**Decisión.** Persistir `expiresAt` una sola vez. La transacción de reserva crea `ExpireReservation` en outbox. Al enviarlo a SQS Standard se calcula `ceil(max(0, expiresAt-now))`, máximo 600 segundos. Un retry conserva el deadline; un mensaje temprano vuelve a quedar invisible por el tiempo restante y no se reconoce. Inventory libera la reserva completa mediante condiciones sobre estado, versión y propietario. Un timer antiguo no libera una asignación nueva ni una venta.

**Recuperación.** EventBridge Scheduler envía `SweepExpiredReservations` cada minuto a la misma cola dedicada; un lease durable/cooldown evita barridos simultáneos entre réplicas. La ejecución tiene timeout menor que su lease y queries paginadas, sin Scan. Localmente un único publicador reproduce ese comando. Consultas y operaciones también verifican vencimiento.

**Consecuencias.** El plazo lógico es estricto, la entrega física puede retrasarse. El barrido satisface recuperación periódica sin ser el camino principal. SQS admite este plazo de diez minutos; para plazos mayores de quince minutos habría que revisar Scheduler por reserva u otra estrategia. Cron como único mecanismo aumenta el atraso normal; Step Functions o una Lambda de negocio adicional introducirían coordinación y un segundo lugar para las invariantes. TTL solo limpia residuos.

## ADR-004 — Estados oficiales y finales irreversibles

**Decisión.** Conservar AVAILABLE, RESERVED, PENDING_CONFIRMATION, SOLD y COMPLIMENTARY. La confirmación no extiende el plazo. SOLD y COMPLIMENTARY son finales; cortesía ocupa capacidad sin sumar ventas. La orden expone además `workflowStatus`, `resolution` y conjuntos de entradas solicitadas/activas.

**Consecuencias.** AVAILABLE en una orden sin asignación no declara libres sus entradas históricas. Confirmación y expiración compiten en la misma autoridad con un solo ganador. No hay pasarela, reembolso ni cancelación de ventas: el PDF no define esos contratos. Incorporarlos exigiría una nueva decisión de negocio, no reutilizar expiración para revertir SOLD.

## ADR-005 — Accesos DynamoDB y coordinación durable

**Decisión.** Modelar claves e índices según accesos por ID, evento, vencimiento y trabajo pendiente. Dieciséis shards distribuyen contadores/índices; el total se lee con snapshot transaccional. Leases de relay y reconciliación reducen consultas duplicadas; condiciones por mensaje/reserva protegen corrección incluso si un lease vence.

**Consecuencias.** El sistema sigue realizando polling de outbox. El máximo backoff vacío es diez segundos; no se promete publicación p95 de un segundo tras inactividad. Los contadores son una vista materializada conservadora; una comprobación de atraso superior a cinco segundos responde 503. La lectura tiene costo adicional de queries. Sharding no acredita capacidad ilimitada: hay que medir contención, latencia y consumo. CDC/Streams/Pipes es una evolución posible, no una capacidad implementada.

## ADR-006 — SQS Standard y emulación local

**Decisión.** Usar SQS Standard, cuatro colas principales con DLQ de consumo y otra DLQ AWS para fallos de entrega del Scheduler. Duplicados y desorden son parte del contrato. DynamoDB Local y ElasticMQ mantienen volúmenes persistentes y permiten ejecutar sin cuenta AWS.

**Consecuencias.** ElasticMQ implementa una API compatible para desarrollo; no es SQS administrado. LocalStack es una alternativa viable, pero cambiar de emulador no verifica IAM, cuotas ni rendimiento de AWS. No se mezclan un error de entrega Scheduler→SQS y un error del consumidor: sus DLQ y runbooks son distintos. El replay conserva envelope, ID y deadline.

## ADR-007 — Scaffold oficial y calidad desde DEV

**Decisión.** Generar con Bancolombia Scaffold 5.2.1 y adaptar sus ejemplos, puertos y capas. Mantener dominio sin dependencias de Spring/AWS y verificar límites con ArchUnit. Separar cobertura unitaria de integración; exigir 90% de líneas/ramas y 80% de mutación en modelo/casos de uso. Cada commit ejecuta análisis estático; Sonar Community analiza develop antes de promover.

**Consecuencias.** Community conserva una sola rama de análisis por proyecto; no se presenta como análisis nativo de todas las PR. Un hook local puede omitirse, por eso CI confiable vuelve a revisar cada SHA. Sonar local se identifica como local, no como ejecución GitHub. Los ejemplos no se eliminan para sustituir silenciosamente el generador por otra estructura.

## ADR-008 — Infraestructura separada y promoción reproducible

**Decisión.** `ticketing-infra` es un repositorio independiente con módulos, roots por ambiente y permisos separados por API/worker. La aplicación sigue feature→develop→release→main. Un candidato cloud referencia el SHA externo de infraestructura y tres digests inmutables; QA/PROD reutilizan imágenes, no recompilan.

**Consecuencias.** Configurar Terraform o ejecutar mocks no prueba AWS. Sin un workflow real y un registro que establezca digests no existe un candidato cloud publicable; IDs locales de imágenes y merges locales no sustituyen esa procedencia. No hay NAT Gateway; endpoints privados, ALB, Fargate y demás componentes sí tienen costo cuando se despliegan. Ningún apply forma parte del arranque local.

## ADR-009 — Observabilidad sin dependencia crítica

**Decisión.** Logs estructurados y correlación de mensajes; métricas HTTP/JVM/SDK vía Prometheus y trazas HTTP con el starter OpenTelemetry de Boot 4. El overlay local incorpora Collector, Tempo y Grafana, con retención acotada y puertos localhost. Exportadores apagados por defecto, timeouts cortos y sin cuerpos ni credenciales en logs.

**Consecuencias.** Métricas de salud no prueban invariantes de negocio. La correlación por IDs asíncronos no equivale a propagación W3C completa a través de SQS. Una caída del backend de trazas no debe impedir reservar. Las métricas del emulador expresan operaciones, no unidades facturadas ni costo AWS.
