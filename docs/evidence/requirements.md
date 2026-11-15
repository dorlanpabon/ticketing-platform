# Trazabilidad de requisitos y evidencia

Fecha de revisión: 2026-11-16. Los **67 identificadores** corresponden a la [matriz del plan](../planning/MATRIZ-REQUISITOS.md). `F`, `D`, `T`, `E` y `V` conservan el origen y la página del PDF; `U` corresponde a decisiones del usuario y `P` a decisiones de diseño. El PDF original es *Prueba 2026 desarrolladores (2).pdf*, ocho páginas. El plan no sustituye los requisitos ni demuestra que se hayan ejecutado las pruebas.

## Cómo leer los estados

- **Implemented**: existe implementación, configuración o documento enlazado; no implica una prueba aprobada.
- **Local verified**: existe evidencia local concreta para el alcance indicado. No significa validación AWS ni aprobación de un commit publicable.
- **Pending Sonar**: falta registrar el análisis y Quality Gate del SHA exacto de DEV. La cobertura JaCoCo no reemplaza Sonar.
- **Pending AWS**: falta despliegue y comprobación en AWS. Terraform validado o un provider simulado no equivalen a un despliegue.
- **Pending performance**: existe método de carga, pero los objetivos de usuarios, duración y percentiles requieren resultados reales del escenario completo.

Los estados pueden coexistir. Las pruebas de desarrollo observadas declaran `dirty=true` o `workingTreeDirty=true` y corresponden a los HEAD registrados en cada reporte, con cambios adicionales. El primer conjunto de aceptación/roles referencia `890d8b8bcdf5ae313a6891e0f1b71d24dc7a0abc`; expiración/observabilidad referencia `8c3e780ef2146c1f766997541d19aba431b5f048`, y persistencia/Newman final `6debcf20f1e52b6f2f9c36008c6682a2b9fc953b`. **No acreditan por sí solos esos commits ni permiten promoverlos.** Los reportes bajo `build/` y `evidence/local/` son artefactos generados, no archivos garantizados en un clon nuevo; adjuntarlos al candidato con hashes, imágenes, fecha y SHA. Los enlaces a pruebas de fuente sí forman parte del repositorio.

## Resultados observados al revisar

| Evidencia | Resultado local | Alcance y límite |
|---|---|---|
| [Aceptación HTTP y recuperación][acceptance-report] | `containerSmoke`, `integration`, `concurrency`, `recovery`: passed. | La [integración][http-integration] verificó aceptación durable, idempotencia, deadline, ownership, confirmación, cortesía, atomicidad y contadores. |
| [Contención HTTP][race-report] | 100 compradores, una reserva ganadora, 99 rechazados. | Escenario local de una entrada; no benchmark de mil usuarios ni prueba de todas las intercalaciones. |
| [Caída y recuperación][recovery-report] | Dos consumidores restaurados; deadline conservado. | Inventory API y consumidores no disponibles, luego restaurados. No acredita recuperación regional AWS. |
| [Roles y provisión][roles-report] | Tres contextos, dos workers Inventory; PROVISIONING → ACTIVE en 67,52 s después de restaurar workers. | Inventory API permaneció lista. Reporte de desarrollo con árbol sucio. |
| [Integración DynamoDB Inventory][inventory-db-report] | 12 pruebas, cero fallos/errores/omitidas. | DynamoDB Local real; incluye carreras, temporizador durable, paginación y leases. El reloj controlado no prueba diez minutos de espera SQS real. |
| [Integración DynamoDB Orders][orders-db-report] | Cuatro pruebas, cero fallos/errores/omitidas. | Atomicidad de creación/idempotencia, confirmación concurrente, proyección y lease outbox. |
| JaCoCo unitario | Catalog: 414/427 líneas y 135/142 ramas; Orders: 531/550 y 205/223; Inventory: 718/741 y 357/386. | Todos superan 90 % en ambos contadores de los XML revisados. [Catalog][catalog-coverage], [Orders][orders-coverage], [Inventory][inventory-coverage]. Integración no se usa para inflar el gate unitario. |
| PIT dominio/casos de uso | Catalog: 13/13 y 22/22; Orders: 33/34 y 44/48; Inventory: 47/52 y 133/147 mutantes eliminados. | Los seis reportes superan 80 %; sin NO_COVERAGE/TIMED_OUT/RUN_ERROR en los XML revisados. [Catalog modelo][catalog-pit-model] / [usecase][catalog-pit-usecase], [Orders modelo][orders-pit-model] / [usecase][orders-pit-usecase], [Inventory modelo][inventory-pit-model] / [usecase][inventory-pit-usecase]. No es PIT de toda infraestructura. |
| [Contratos][contracts] | OpenAPI y AsyncAPI aprobados por validadores oficiales; contraste de 12 rutas y siete records. | Especificación y estructura; no sustituyen pruebas de comportamiento. |
| [Expiración SQS real][expiry-report] | Espera de diez minutos; timer procesado 0,520293 s después del deadline; estado durable RESERVATION_EXPIRED y SOLD conservado. | [Reporte padre][expiry-parent] con dirty=true. Inventory API detenida y publicador periódico detenido: no hubo GET que liberara ni Sweep auxiliar. Un caso local no establece p95 ni puntualidad universal. |
| [Observabilidad real][observability-report] | Tres APIs/cuatro workers UP, trazas HTTP Catalog y Orders→Inventory enlazadas, cinco paneles con series finitas. | 09:54:22–09:54:33 UTC, dirty=true; no propagación SQS ni CloudWatch AWS verificados. [Detalle][observability-doc]. |
| [Persistencia tras reinicio][persistence-report] | Ítem DynamoDB y body/MessageId SQS conservados; limpieza sin fallo reportado. | 10:07:09–10:07:36 UTC, workingTreeDirty=true; una tabla/cola aisladas. Reinicio de emuladores, no backup/restore ni recreación completa/AWS. |
| [Colección Postman real][postman-report] | Doce etapas, 34 solicitudes y 42 aserciones, cero fallos. | 10:08:01–10:08:24 UTC, workingTreeDirty=true. Newman externo 6.2.3; auditoría de dependencias fallida registrada separadamente. No es gate de promoción. |

Hashes SHA-256 de los reportes revisados: expiración `e91c24080dee167a27ae4ceb275e7acf04750e5c0939e10c372fc72b22d36229`; observabilidad `4bded484e6e0e788a95cc01f12ed445040f5c858accb662b81b58eb0022b9e90`; persistencia `2f112027272bad68baed69b66dc2eff807653afdcf69b2fe20ad42fe7f9359d8`; Postman `d70701d6eaf8365305b1116f1f782e60fee7e34ffea49715e9f44aeebdfc31cf`.

La expiración real, las trazas y los paneles tienen ahora evidencia local acotada. Siguen pendientes el SAST final por commit, Sonar del candidato final, carga completa y AWS. El grafo oficial Newman falló npm audit (12 high, una critical, seis moderate); se conserva fuera del repositorio de aplicación como herramienta opcional auditada, sin supresiones ni aprobación de seguridad. Una medición SDK con escenario `recovery-active` no acredita el presupuesto de consultas en reposo ni costos monetarios.

## Matriz completa

| ID | Origen | Requisito / decisión | Estado | Implementación, prueba y límite |
|---|---|---|---|---|
| F01 | PDF p.3 RF1 | Crear/consultar eventos e inventario. | Local verified | [Catalog use case][catalog-tests], [HTTP][catalog-http], [provisión y recuperación][roles-report]. PROVISIONING impide anunciar inventario incompleto. |
| F02 | PDF p.3 RF2 | Reserva de máximo diez minutos y devolución si no se confirma. | Local verified | [Fronteras de reloj][inventory-tests], [DynamoDB][inventory-db] y [timer SQS tras diez minutos reales][expiry-report]; liberación íntegra, sin extensión del deadline ni liberación de SOLD. |
| F03 | PDF p.3 RF3 | Encolar solicitud, retornar ID y procesar compra asíncrona. | Local verified | [Orders][orders-tests], [SQS][inventory-sqs], [aceptación][http-integration]. El 202 garantiza orden + outbox persistidos; no garantiza presencia física inmediata en SQS. |
| F04 | PDF p.3 RF4 | Consultar orden por ID y estado de entradas. | Local verified | [Orders HTTP][orders-http] y [use case][orders-tests]. Se distinguen `status` de inventario y `workflowStatus` técnico; Inventory es autoridad. |
| F05 | PDF p.4 RF5 | Carreras y ausencia de sobreventa. | Local verified | [100 compradores][race-report], [transacciones reales][inventory-db]. Pendiente carga sostenida y AWS. |
| F06 | PDF p.4 RF6 | Liberación automática mediante proceso periódico. | Local verified / Pending AWS | [Recovery paginado y lease][inventory-db], [emisor local cada minuto][recovery-tick]. Timer SQS por reserva es el mecanismo principal; Sweep periódico es recuperación. Scheduler AWS no desplegado. |
| F07 | PDF p.4 RF7 | Disponibilidad actual por endpoint reactivo. | Local verified | [Snapshot transaccional y stale gate][inventory-db], [HTTP][inventory-http]. Puede informar contadores conservadores hasta cinco segundos; devuelve 503 si el vencimiento sin materializar supera ese límite. No se promete exactitud instantánea de liberación. |
| D01 | PDF p.2 | Exactamente uno de cinco estados por entrada. | Local verified | [Modelo y transiciones][inventory-tests], [integración][inventory-db]. |
| D02 | PDF p.2 | Transiciones atómicas y auditables. | Local verified | [Adaptador transaccional][inventory-adapter], [carreras y all-or-none][inventory-db]; auditoría y outbox se escriben con el cambio. No es transacción entre servicios. |
| D03 | PDF p.2 | RESERVED/PENDING_CONFIRMATION no son ventas. | Local verified | [Contadores][inventory-db], [reglas][inventory-tests]. Solo SOLD suma ventas. |
| D04 | PDF p.2 | SOLD final e irreversible. | Local verified | [Carrera confirmar/expirar y replay][inventory-db], [proyección Orders][orders-db]. No hay endpoint de reversión de venta. |
| D05 | PDF p.2 | COMPLIMENTARY final y no contable. | Local verified | [Cortesía y conservación de contadores][http-integration], [DynamoDB][inventory-db]. Consume capacidad, no venta. |
| D06 | PDF pp.1–3 | Evitar duplicados, doble titularidad y sobreventa. | Local verified | [Idempotencia y confirmaciones concurrentes][orders-db], [reservas concurrentes][inventory-db]. |
| T01 | PDF p.4 | Java 25; características modernas cuando apliquen. | Local verified | [Build Catalog][catalog-build], [Dockerfile][dockerfile]; records, switch y clientes asíncronos. No se añaden Virtual Threads a operaciones ya no bloqueantes. |
| T02 | PDF p.4 | Spring Boot 4.x. | Local verified | [Scaffold y versiones][catalog-settings], [app build][catalog-build]; Boot 4.1.2. |
| T03 | PDF pp.3–5 | WebFlux y operaciones no bloqueantes. | Local verified | [HTTP reactivo][orders-http], [límites arquitectónicos][architecture-test]. Clientes SDK async/WebClient; falta caracterización bajo carga. |
| T04 | PDF pp.3–4 | Persistencia NoSQL de eventos, órdenes e inventario. | Local verified / Pending AWS | [Tablas locales][bootstrap], [transacciones Inventory][inventory-db], [Orders][orders-db]. Cada contexto posee su tabla. |
| T05 | PDF pp.4–5 | SQS asíncrono, entrega al menos una vez. | Local verified / Pending AWS | [Contrato asíncrono][asyncapi], [consumer][inventory-sqs], [sender][inventory-sender]. ElasticMQ no certifica IAM/KMS/SQS real. |
| T06 | PDF p.4 | Docker en aplicación y dependencias. | Local verified | [Compose][compose], [Dockerfile][dockerfile], [roles efectivos][roles-report]. |
| T07 | PDF pp.4–5 | Clean Architecture. | Implemented | [Arquitectura][architecture], [tests de límites][architecture-test]. El scaffold conserva dominio, casos de uso, adaptadores y aplicación. |
| T08 | PDF p.5 | API con Mono/Flux. | Local verified | [Pruebas WebFlux][orders-http], [Inventory HTTP][inventory-http]. |
| T09 | PDF p.5 | Errores y retries reactivos apropiados. | Local verified | [Use cases][inventory-tests], [listener][inventory-listener], [DynamoDB][inventory-db]. Retries acotados, sin extender deadline; no reintentar errores de negocio como disponibilidad técnica. |
| T10 | PDF p.5 | Versiones/conditional writes en inventario. | Local verified | [Adaptador][inventory-adapter], [carreras reales][inventory-db]. |
| T11 | PDF pp.5–6 | Compose con aplicación y dependencias. | Local verified | [compose.yaml][compose] es el nombre convencional equivalente a `docker-compose.yml`; [runbook][runbook]. |
| E01 | PDF p.5 | Fuente con estructura Clean Architecture. | Implemented | [Scaffold oficial configurado][catalog-settings], [arquitectura][architecture]. |
| E02 | PDF p.5 | Identificadores y comentarios de código en inglés. | Implemented | [Dominio][inventory-usecase], [adaptadores][inventory-adapter]. Documentación en español; revisión lingüística humana no automatizada. |
| E03 | PDF p.5 | SOLID y patrones apropiados. | Implemented | [Puertos/casos de uso][inventory-usecase], [decisiones][architecture]. Juicio de diseño sustentable, no puntuación automática. |
| E04 | PDF pp.5–6 | README con setup, configuración, decisiones y ejemplos. | Implemented | [README][readme], [runbook][runbook], [contratos][contracts], [colección][postman]. |
| E05 | PDF p.6 | Cobertura unitaria mínima del 90 %. | Local verified / Pending Sonar | [Gate][quality-gradle] y tres reportes JaCoCo indicados arriba. Pendiente importación/análisis Sonar del SHA final. |
| E06 | PDF p.6 | Casos de uso con mocks de repositorios. | Local verified | [Catalog][catalog-tests], [Orders][orders-tests], [Inventory][inventory-tests]. No se presentan mocks como prueba de atomicidad DynamoDB. |
| E07 | PDF p.6 | Pruebas de componentes WebFlux/servicios. | Local verified | [Catalog][catalog-http], [Orders][orders-http], [Inventory][inventory-http]; incluye body vacío y errores de autorización. |
| E08 | PDF p.6 | Pruebas de concurrencia. | Local verified | [DynamoDB][inventory-db], [confirmación Orders][orders-db], [100 compradores HTTP][race-report]. |
| E09 | PDF p.6 | JUnit, Mockito y reactor-test. | Local verified | [Unitarios reactivos][inventory-tests], [pruebas HTTP][orders-http]. |
| E10 | PDF p.6 | Postman/Insomnia/curl para flujos principales. | Local verified | [Colección][postman] ejecutada completa: [34 requests/42 assertions][postman-report]. [Runner opcional y auditoría fallida de Newman][postman-runner]; la colección funciona independientemente en GUI, no reemplaza aceptación Python. |
| E11 | PDF p.6 | Sustentación técnica estructurada. | Implemented | [Guía de sustentación][presentation], [arquitectura][architecture]. Ensayo y evaluación humana pendientes. |
| V01 | PDF p.7 | Coherencia y cumplimiento funcional. | Local verified | [Suite de aceptación][acceptance-source] y matices F03/F07. El corte local no sustituye revisión del evaluador. |
| V02 | PDF p.7 | Justificar consistencia, patrones y asincronía. | Implemented | [Arquitectura][architecture], [contratos][contracts], [guía][presentation]. |
| V03 | PDF p.7 adicional | Escalabilidad, resiliencia y tolerancia a fallos. | Local verified / Pending AWS / Pending performance | [Dos workers y caída][recovery-report], [leases][inventory-db]. Escalado horizontal cloud y carga sostenida pendientes. |
| V04 | PDF p.7 | Gestión segura de secretos y credenciales. | Implemented / Pending AWS | [Threat model][security], [seguridad IaC][infra-security]. Credenciales sintéticas solo emuladores; escaneo final de secretos pendiente por SHA. |
| V05 | PDF p.7 | Abuso, retries maliciosos e idempotencia. | Local verified / Pending AWS | [Ownership y errores][orders-http], [idempotencia concurrente][orders-db], [amenazas residuales][security]. Sin rate limiter local; throttling AWS pendiente de verificar. |
| V06 | PDF pp.7–8 | Diagramas y explicación clara. | Implemented | [Arquitectura actual][architecture], [diagramas del plan][diagrams]. El runtime prevalece sobre detalles históricos del plan. |
| V07 | PDF p.8 diferencial | Terraform y aislamiento de infraestructura. | Implemented / Pending AWS | [Repositorio de infraestructura][infra], [verificación sin AWS][infra-verification]. |
| V08 | PDF p.8 diferencial | Prácticas AWS y operación cloud-native. | Implemented / Pending AWS | [Infraestructura][infra], [runbook][runbook]. No se atribuyen estas decisiones a prácticas internas de Nequi. |
| V09 | PDF p.8 | Costos, observabilidad y gobernanza. | Local verified / Pending AWS / Pending performance | [Trazas y paneles locales][observability-report], [arquitectura][architecture], [CI][ci]. Costo real AWS, alarmas cloud y carga siguen pendientes; métricas SDK locales no son costos facturados. |
| V10 | PDF p.8 | Limitaciones y mejoras para producción. | Implemented | [Seguridad][security], [rendimiento][performance], [límites contractuales][contracts]. |
| U01 | Usuario | Proyecto desde cero sin contexto anterior. | Implemented | [Plan registrado][plan] y estructura nueva. Es una decisión de proceso, no una propiedad demostrable con test funcional. |
| U02 | Usuario | Scaffold Bancolombia. | Implemented | [Plugin oficial][catalog-settings] y estructuras generadas conservadas/adaptadas. |
| U03 | Usuario | Catalog, Orders e Inventory separados. | Local verified | [Arquitectura][architecture], [seis roles de tres imágenes][roles-report]. |
| U04 | Usuario | Confirmación explícita y entradas numeradas. | Local verified | [HTTP integrado][http-integration], [modelo][inventory-tests]. Selección de 1–10 entradas distintas, no capacidad máxima de diez. |
| U05 | Usuario | Local completo y AWS desplegable sin gasto obligatorio. | Local verified / Pending AWS | [Compose][compose], [IaC separada][infra]. No se ejecutó apply; ECS/endpoints/KMS desplegados sí generarían cargos. |
| U06 | Usuario | Hitos 13–15 de noviembre de 2026. | Implemented | [Plan][plan] conserva cronograma como planificación; trabajo ejecutado y commits usan fecha real. No se fabrican commits retrospectivos. |
| U07 | Usuario | main/develop/release y flujo incremental. | Implemented | [Política de ramas][ci], [tests de promoción][candidate-tests]. Protección remota y ciclo completo requieren evidencia del repositorio remoto. |
| U08 | Usuario | Configuración por ambiente desde IaC. | Implemented / Pending AWS | [Contrato app/infra][infra-app], [deployment][infra-deployment]. Local usa Compose; no se mezclan credenciales cloud. |
| U09 | Usuario | Sonar local gratuito. | Implemented / Pending Sonar | [Perfil quality][compose], [setup][sonar-setup]. Instalar/provisionar Sonar no equivale a aprobar análisis. |
| U10 | Usuario | Sonar en DEV, antes de main. | Implemented / Pending Sonar | [Runner de develop][sonar-develop], [CI][ci]. Exige evento/ref/SHA y análisis específico. |
| U11 | Usuario | Escaneo estático en cada commit. | Implemented | [Hook][hook], [CI por commit][ci], [pruebas de política][quality-tests]. Historial final/escaneos por SHA aún pendientes; hook local es eludible. |
| U12 | Usuario | Documentación, diagramas y sustentación. | Implemented | [Guía][presentation], [arquitectura][architecture], [runbook][runbook] y esta matriz. |
| U13 | Usuario | AWS, IaC modular y Checkov. | Implemented / Pending AWS | [Verificación IaC][infra-verification], [excepciones Checkov justificadas][infra-security]. No se equipara un escaneo local al SAST final de aplicación. |
| P01 | Diseño | 16 shards y snapshot transaccional de contadores. | Local verified | [Adaptador][inventory-adapter], [invariantes][inventory-db]. La disponibilidad añade consultas de frescura; fan-out/costo requieren medición. |
| P02 | Diseño | JaCoCo ≥90 % líneas y ramas; importación Sonar. | Local verified / Pending Sonar | [Gate unitario][quality-gradle], tres XML de cobertura; importación Sonar pendiente. |
| P03 | Diseño | PIT dominio/casos de uso ≥80 %. | Local verified | [Gate][quality-gradle] y reportes PIT locales de dominio/usecase. Revalidar tras cambio funcional; no se exige PIT sobre toda infraestructura. |
| P04 | Diseño | 1000 usuarios y objetivos p95, cero sobreventa. | Implemented / Pending performance | [Método y límites][performance]. Scripts/validación k6 no demuestran throughput ni objetivos; 1000 VUs no equivalen a 1000 requests simultáneos. |
| P05 | Diseño | Promover mismos digests DEV→QA→PROD. | Implemented / Pending Sonar / Pending AWS | [Candidato y pruebas][candidate-tests], [infra deployment][infra-deployment]. No hay promoción productiva demostrada. |
| P06 | Diseño | Congelar release y recorrer flujo completo para fixes. | Implemented | [CI/política][ci], [tests][quality-tests]. Protección remota y ejecución real pendientes. |
| P07 | Diseño | Serializar Sonar por proyecto y verificar análisis concreto. | Implemented / Pending Sonar | [Runner][sonar-develop] y [CI][ci]; ceTaskId/analysisId/SHA, no solo último gate de proyecto. |
| P08 | Diseño | Política no puede autoaprobar su desactivación. | Implemented | [CI confiable][ci], [tests de política][quality-tests]. El repositorio remoto debe proteger workflows, revisiones y checks. |
| P09 | Diseño | Rollback de artefactos sin revertir finales. | Local verified / Pending AWS | [SOLD/COMPLIMENTARY finales][inventory-db], [runbook][runbook]. Prueba de rollback AWS y recuperación de backup pendientes. |

## Diferencias deliberadas y pendientes

- No hay pasarela de pagos, cancelación manual, reembolso ni reversión de SOLD. El PDF exige expiración sin confirmación y estado final irreversible; no exige pagos ni cancelación manual.
- La política de cinco segundos para disponibilidad, 16 shards, saga de tres contextos, timers por reserva, gates de ramas/PIT y metas p95 son decisiones del plan. El PDF exige un máximo de diez minutos para la reserva y proceso periódico de liberación, sin fijar ese umbral de cinco segundos.
- La versión inicial del plan que describía un barrido cada segundo queda sustituida por timer SQS + recuperación cada minuto coordinada por lease. Véase [arquitectura actual][architecture].
- SQS puede retrasarse y duplicar. El deadline persistido limita toda transición; restablecer workers o hacer replay nunca concede otros diez minutos.
- Falta cerrar evidencia del SHA definitivo: SAST completo, Sonar DEV, aceptación/expiración y observabilidad del artefacto final, rendimiento sostenido y promoción. AWS permanece sin desplegar.

[plan]: ../planning/PLAN-MAESTRO.md
[diagrams]: ../planning/DIAGRAMAS.md
[presentation]: ../planning/GUIA-SUSTENTACION.md
[readme]: ../../README.md
[runbook]: ../runbook.md
[security]: ../security.md
[architecture]: ../architecture-runtime.md
[performance]: ../performance.md
[contracts]: ../contracts.md
[compose]: ../../compose.yaml
[observability]: ../../compose.observability.yaml
[dockerfile]: ../../Dockerfile
[asyncapi]: ../../contracts/asyncapi.json
[postman]: ../../contracts/ticketing.postman_collection.json
[bootstrap]: ../../scripts/local/bootstrap.sh
[recovery-tick]: ../../scripts/local/recovery-tick.sh
[acceptance-source]: ../../scripts/acceptance/verify.py
[acceptance-report]: ../../build/quality/acceptance-runtime.json
[http-integration]: ../../build/quality/acceptance/integration.json
[race-report]: ../../build/quality/acceptance/concurrency.json
[recovery-report]: ../../build/quality/acceptance/recovery.json
[expiry-parent]: ../../build/quality/acceptance-expiry-fixed-dev.json
[expiry-report]: ../../build/quality/acceptance-expiry-fixed-dev-evidence/expiry.json
[observability-report]: ../../build/quality/observability-dev.json
[observability-doc]: ../observability.md
[persistence-report]: ../../build/quality/persistence-dev.json
[postman-report]: ../../evidence/local/postman/2026-11-16T10-08-01-052Z-58c1810b-0317-4e4a-9232-5829e6d7eda6/report.json
[postman-runner]: ../../scripts/postman/README.md
[roles-report]: ../../build/quality/runtime-roles.json
[catalog-tests]: ../../services/catalog/domain/usecase/src/test/java/com/ticketing/catalog/usecase/CatalogUseCaseTest.java
[catalog-http]: ../../services/catalog/infrastructure/entry-points/reactive-web/src/test/java/com/ticketing/catalog/api/RouterRestTest.java
[catalog-settings]: ../../services/catalog/settings.gradle
[catalog-build]: ../../services/catalog/applications/app-service/build.gradle
[catalog-coverage]: ../../services/catalog/build/reports/jacoco/merged/jacoco.xml
[catalog-pit-model]: ../../services/catalog/domain/model/build/reports/pitest/mutations.xml
[catalog-pit-usecase]: ../../services/catalog/domain/usecase/build/reports/pitest/mutations.xml
[orders-tests]: ../../services/orders/domain/usecase/src/test/java/com/ticketing/orders/usecase/OrdersUseCaseTest.java
[orders-http]: ../../services/orders/infrastructure/entry-points/reactive-web/src/test/java/com/ticketing/orders/api/RouterRestTest.java
[orders-db]: ../../services/orders/infrastructure/driven-adapters/dynamo-db/src/test/java/com/ticketing/orders/dynamodb/OrdersDynamoIntegrationTest.java
[orders-db-report]: ../../services/orders/infrastructure/driven-adapters/dynamo-db/build/test-results/integrationTest/TEST-com.ticketing.orders.dynamodb.OrdersDynamoIntegrationTest.xml
[orders-coverage]: ../../services/orders/build/reports/jacoco/merged/jacoco.xml
[orders-pit-model]: ../../services/orders/domain/model/build/reports/pitest/mutations.xml
[orders-pit-usecase]: ../../services/orders/domain/usecase/build/reports/pitest/mutations.xml
[inventory-tests]: ../../services/inventory/domain/usecase/src/test/java/com/ticketing/inventory/usecase/InventoryUseCaseTest.java
[inventory-usecase]: ../../services/inventory/domain/usecase/src/main/java/com/ticketing/inventory/usecase/InventoryUseCase.java
[inventory-db]: ../../services/inventory/infrastructure/driven-adapters/dynamo-db/src/test/java/com/ticketing/inventory/dynamodb/DynamoInventoryIntegrationTest.java
[inventory-db-report]: ../../services/inventory/infrastructure/driven-adapters/dynamo-db/build/test-results/integrationTest/TEST-com.ticketing.inventory.dynamodb.DynamoInventoryIntegrationTest.xml
[inventory-adapter]: ../../services/inventory/infrastructure/driven-adapters/dynamo-db/src/main/java/com/ticketing/inventory/dynamodb/DynamoDBTemplateAdapter.java
[inventory-http]: ../../services/inventory/infrastructure/entry-points/reactive-web/src/test/java/com/ticketing/inventory/api/InventoryApiTest.java
[inventory-sqs]: ../../services/inventory/infrastructure/entry-points/sqs-listener/src/test/java/com/ticketing/inventory/sqs/listener/SQSProcessorTest.java
[inventory-listener]: ../../services/inventory/infrastructure/entry-points/sqs-listener/src/test/java/com/ticketing/inventory/sqs/listener/helper/SQSListenerTest.java
[inventory-sender]: ../../services/inventory/infrastructure/driven-adapters/sqs-sender/src/test/java/com/ticketing/inventory/sqs/sender/SQSSenderTest.java
[inventory-coverage]: ../../services/inventory/build/reports/jacoco/merged/jacoco.xml
[inventory-pit-model]: ../../services/inventory/domain/model/build/reports/pitest/mutations.xml
[inventory-pit-usecase]: ../../services/inventory/domain/usecase/build/reports/pitest/mutations.xml
[architecture-test]: ../../services/orders/applications/app-service/src/test/java/com/ticketing/orders/StrictArchitectureTest.java
[quality-gradle]: ../../gradle/quality.gradle
[ci]: ../../.github/workflows/quality.yml
[quality-tests]: ../../scripts/quality/tests/test_quality.py
[candidate-tests]: ../../scripts/quality/tests/test_candidate.py
[hook]: ../../.githooks/pre-commit
[sonar-setup]: ../../scripts/quality/sonar_setup.py
[sonar-develop]: ../../scripts/quality/sonar_develop.py
[infra]: ../../../ticketing-infra/README.md
[infra-security]: ../../../ticketing-infra/docs/security.md
[infra-verification]: ../../../ticketing-infra/docs/verification.md
[infra-app]: ../../../ticketing-infra/docs/application-contract.md
[infra-deployment]: ../../../ticketing-infra/docs/deployment.md
