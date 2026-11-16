# Operación y recuperación

Este runbook corresponde a [la arquitectura implementada](architecture-runtime.md) y los [contratos](contracts.md). [La matriz de evidencia](evidence/requirements.md) distingue implementación, pruebas locales y validaciones pendientes. Los comandos siguientes se ejecutan desde la raíz de `ticketing-platform`, salvo indicación contraria. No ejecutar pruebas de caída, carga o reconstrucciones mientras otra prueba de aceptación utiliza el mismo entorno.

## Arranque local

Requisitos: Docker Engine con Compose v2 y contenedores Linux; Python 3 para scripts. El Dockerfile construye con Java 25/Gradle fijados. Para pruebas fuera de Docker se requiere JDK 25 y el wrapper del servicio. El entorno normal ejecuta seis JVM: tres APIs y tres workers, 512 MiB por proceso; los perfiles opcionales consumen memoria adicional. Revisar `docker info` antes de añadir réplicas o carga.

```powershell
docker compose config --quiet
docker compose up --build --wait
docker compose ps
curl.exe --fail http://localhost:18080/actuator/health/readiness
curl.exe --fail http://localhost:18081/actuator/health/readiness
curl.exe --fail http://localhost:18082/actuator/health/readiness
```

| Componente | Dirección local |
|---|---|
| Catalog API | `http://localhost:18080` |
| Orders API | `http://localhost:18081` |
| Inventory API | `http://localhost:18082` |
| DynamoDB Local | `http://localhost:18000` |
| ElasticMQ | `http://localhost:19324` |

Los puertos publicados se vinculan a loopback. Los workers no publican puertos de host ni atienden rutas de negocio; conservan health y Prometheus. `bootstrap` crea tablas/índices/colas y configura redrive sin reemplazar datos de negocio. Esperar su terminación exitosa, no iniciar APIs sobre una inicialización fallida. `expiry-recovery` debe ser **una sola instancia**: publica el mensaje periódico; los consumers coordinan su ejecución mediante lease durable.

Identidades de prueba: `Authorization: Bearer local-customer-1`, `local-customer-2` o `local-admin`. Solo funcionan con perfil `local` y `AUTH_MODE=local`. No son JWT ni credenciales cloud. Los valores AWS sintéticos de Compose permiten firmar solicitudes a emuladores; no dan acceso a AWS. Los ejemplos funcionales están en [Postman](../contracts/ticketing.postman_collection.json) y [OpenAPI](../contracts/openapi.json). Confirmar una orden es una operación explícita independiente de crearla; `202` no demuestra que ya esté reservada o vendida.

## Configuración efectiva

| Variable | Uso / regla |
|---|---|
| `APP_ROLE` | Etiqueta `api` o `worker`; no sustituye los flags que controlan ejecución. |
| `API_ENABLED`, `WORKERS_ENABLED` | API: true/false; worker: false/true. APIs no deben iniciar consumidores. |
| `SPRING_PROFILES_ACTIVE`, `AUTH_MODE` | Local: `local`/`local`; AWS: identidad del gateway confiable, sin perfil local. |
| `DYNAMODB_TABLE_NAME` | Una tabla propiedad del contexto; no apuntar tres servicios a una misma tabla. |
| `AWS_REGION` | Región de clientes SDK. AWS usa credenciales del task role. |
| `DYNAMODB_ENDPOINT`, `SQS_ENDPOINT` | Solo endpoints de emulador en local; omitir en AWS. |
| `INVENTORY_COMMANDS_QUEUE_URL` | ProvisionInventory, PurchaseRequested, ComplimentaryRequested, ConfirmPurchase. |
| `INVENTORY_EXPIRATIONS_QUEUE_URL` | ExpireReservation y SweepExpiredReservations; no mezclar con compras. |
| `ORDER_EVENTS_QUEUE_URL`, `CATALOG_EVENTS_QUEUE_URL` | Proyecciones y activación de eventos. |
| `INVENTORY_BASE_URL`, `CATALOG_BASE_URL` | Direcciones internas entre servicios; no URLs del frontend ni rutas AWS públicas. |
| `INVENTORY_HTTP_TIMEOUT_MS`, `INVENTORY_REQUEST_TIMEOUT` | Orders usa el primero para conexión y E/S (2000 ms) y el segundo para la operación HTTP completa (2s). |
| `ORDER_ENQUEUE_TIMEOUT`, `ORDER_ENQUEUE_RETRY_DELAY` | Presupuesto para confirmar el envío (8 s) e intervalo entre comprobaciones cuando otro publicador tiene el lease (200 ms). |
| `INVENTORY_DEFAULT_PAGE_SIZE` | Tamaño de página de tickets que Inventory usa cuando el cliente omite `limit` (50). |
| `INVENTORY_TRANSITION_RETRIES`, `INVENTORY_PROCESSING_CONCURRENCY` | Reintentos ante transiciones concurrentes (2) y operaciones simultáneas al liberar reservas (8). |
| `INVENTORY_OUTBOX_PUBLISH_CONCURRENCY`, `INVENTORY_OUTBOX_DISPATCH_TIMEOUT` | Publicaciones simultáneas (8) y presupuesto por lote (15 s), limitado por el tiempo restante del lease del coordinador. |
| `LOCAL_RECOVERY_ENABLED` | False en Compose y AWS. Compose ya tiene el emisor periódico único; no habilitar otro barrido por réplica. |
| `TRACING_ENABLED` | False por defecto; true en overlay observability. |
| `TRACING_SAMPLE_PROBABILITY` | 0.1 por defecto; 1.0 local para diagnosticar, no garantía de correlación completa. |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | Destino OTLP HTTP de trazas; por defecto collector interno, puerto 4318. |

La referencia exacta es [Compose](../docker-compose.yml) y `applications/app-service/src/main/resources/application.yaml` de cada servicio. No versionar `.env`, contraseñas, tokens Sonar ni credenciales AWS. La configuración cloud pertenece a [ticketing-infra](../../ticketing-infra/README.md), no a un segundo Terraform dentro de este repositorio.

## Comprobaciones y evidencia

```powershell
docker compose up -d --wait --scale inventory-worker=2
python scripts/local/verify_roles.py --min-inventory-workers 2
```

`verify_roles.py` comprueba procesos, flags, puertos, límites y atributos de la cola. La opción `--provision-recovery` **detiene y restaura workers y crea un evento**; ejecutarla solo en una ventana de prueba aislada. La suite siguiente también crea datos, provoca caídas y espera vencimientos reales:

```powershell
python scripts/acceptance/verify.py --allow-dirty
```

`--allow-dirty` produce evidencia de desarrollo no promocionable. Para un candidato cerrado, ejecutar sin esa opción y conservar reportes con el SHA exacto e imágenes reales. El check `expiry` requiere dos workers, espera al menos diez minutos, detiene temporalmente Inventory API y el emisor de recuperación, observa la fila DynamoDB y el log del consumer; no usa un GET que pueda liberar la reserva por expiración perezosa. Restaura servicios en `finally`, pero una interrupción forzada del proceso puede impedirlo.

Carga/percentiles requieren [el procedimiento de rendimiento](performance.md); no se ejecutan como parte de un diagnóstico de salud rutinario.

## Restaurar después de una interrupción

```powershell
docker compose up -d --no-deps --wait --scale inventory-worker=2 inventory inventory-worker expiry-recovery
docker compose up -d --no-deps --wait catalog catalog-worker orders orders-worker
docker compose ps
docker compose logs --since 10m --tail 100 inventory-worker orders-worker catalog-worker
```

No incrementar `requestedAt`/`expiresAt`, editar estados finales ni borrar idempotencia para forzar recuperación. Una orden cuyo deadline pasó debe terminar expirada/rechazada, aunque antes no hubiera llegado a Inventory. Si las dependencias también cayeron, usar `docker compose up -d --wait` para restaurarlas antes de reanudar comprobaciones.

`docker compose stop` conserva contenedores y volúmenes; `docker compose down` elimina contenedores/red pero conserva los volúmenes nombrados. No usar `down -v`, purgar colas o borrar tablas como solución de un incidente: destruye evidencia, reservas, outbox e idempotencia. No hay procedimiento automatizado de backup/restauración local certificado; una copia consistente exige detener escritores y respaldar conjuntamente los volúmenes. AWS PITR y recuperación regional requieren ensayo independiente descrito en el repositorio de infraestructura.

### Probe de persistencia de emuladores

El script [verify_persistence.py](../scripts/local/verify_persistence.py) requiere una ventana exclusiva: reinicia DynamoDB Local y ElasticMQ, por lo que interrumpe temporalmente dependencias de todas las APIs/workers. No ejecutarlo durante la prueba de diez minutos, Newman, observabilidad, concurrencia o carga.

```powershell
python scripts/local/verify_persistence.py --output build/quality/persistence.json
docker compose ps
```

El probe crea una tabla y una cola con nombres únicos `ticketing-persistence-<uuid>`, escribe un ítem y un mensaje, ejecuta `docker compose restart dynamodb elasticmq`, espera recuperación y comprueba ítem/body/MessageId conservados. Su limpieza elimina exclusivamente la tabla/cola creadas por esa invocación; un fallo de limpieza deja el resultado fallido. No borra volúmenes ni purga colas de negocio.

El alcance es un reinicio de contenedores con volúmenes existentes, no recreación de toda la plataforma, respaldo/restauración, crash por pérdida de disco ni recuperación AWS. Incluso si pasa, no demuestra que todas las ventas/órdenes sobrevivan cualquier desastre. Conservar `persistence.json` con SHA, workingTreeDirty, fechas y alcance; verificar salud de APIs/workers antes de comenzar otra prueba. Una interrupción forzada puede dejar los recursos aislados del probe para inspección; no usar una limpieza global para retirarlos.

La ejecución local del 2026-11-16, 10:07:09–10:07:36 UTC, terminó `passed`: ítem y mensaje preservados, sin fallos de limpieza reportados. [persistence-dev.json](../build/quality/persistence-dev.json) referencia HEAD `6debcf20f1e52b6f2f9c36008c6682a2b9fc953b` con `workingTreeDirty=true`; es evidencia de desarrollo con el alcance limitado anterior, no un restore certificado ni aprobación del commit final.

## Diagnóstico por síntoma

| Síntoma | Comprobar | Acción segura |
|---|---|---|
| Evento permanece PROVISIONING | Catalog outbox, cola commands, Inventory worker, chunks de provisión e InventoryReady. | Restaurar consumers; reentrega idempotente continúa chunks. No marcar ACTIVE manualmente. |
| Orden RESERVING / 202 sin reserva visible | Salud Inventory, outbox Orders y comandos pendientes. | Esperar/reconciliar con IDs originales. Una API caída no implica que su transacción no se haya confirmado. |
| 503 ORDER_ENQUEUE_UNAVAILABLE | Orders carece de confirmación del envío dentro del presupuesto. | Reintentar el mismo cuerpo con la misma clave de idempotencia para recuperar la orden y el mensaje persistidos. |
| 503 al consultar orden | Inventory inaccesible/timeout. | Recuperar autoridad; no presentar proyección vieja como confirmación definitiva. |
| 503 INVENTORY_STALE | Vencimientos no materializados hace más de cinco segundos. | Revisar cola expirations, worker y recovery; preservar fail-closed. |
| 409 de inventario/idempotencia | Entrada ocupada, versión en carrera o misma clave con payload diferente. | No tratarlo como fallo técnico indiscriminado. Reutilizar clave solo para la misma intención. |
| 410 RESERVATION_EXPIRED | Deadline original alcanzado. | No reactivar orden. Una nueva compra necesita intención, clave y orden nuevas. |
| Reserva no liberada físicamente al vencer | Timer durable, outbox, delayed/visible/in-flight SQS, errores de consumer. | ExpireReservation o Sweep recuperan con deadline original. SOLD/COMPLIMENTARY nunca se liberan. |
| Aumenta backlog / DLQ | Tipo/schema, error class, salud DynamoDB y permisos de destino. | Corregir causa y hacer replay controlado; aumentar réplicas solo si el cuello de botella lo permite. |
| Worker reiniciado y no publica de inmediato | Coordinador y leases con `leaseUntil`; followers en backoff. | Esperar vencimiento válido, no borrar lease de otro propietario. |

## Leases, outbox y expiración

El outbox se escribe en la misma transacción del cambio de negocio. El coordinador outbox tiene lease durable de 30 s con renovación; además cada mensaje tiene propietario/lease para impedir ACK ajeno. El propietario muerto permite takeover tras el vencimiento; la recuperación completa también depende de scheduling, red y disponibilidad de DynamoDB. No es una promesa de extremo a extremo de 30 s.

El polling es secuencial y adaptativo: actividad vuelve a 500 ms; reposo aumenta hasta 10 s con jitter acotado. Los followers esperan según el lease observado. Tras inactividad una nueva intención puede esperar ese backoff; no borrar filas para acelerar una demo. Caída después de publicar pero antes de ACK puede duplicar el mensaje, por lo que todos los consumidores deben preservar idempotencia y versiones.

Cada reserva normal persiste un timer outbox con `{orderId, expiresAt}`. Al publicar se recalcula `DelaySeconds=ceil(max(0, expiresAt-now))`, con tope 600; no se reemplaza el deadline. Un mensaje temprano conserva el mismo deadline y se difiere mediante visibilidad; un timer antiguo, duplicado o para una venta final no libera otra reserva.

SweepExpiredReservations se emite cada minuto. El consumer adquiere lease global **antes** de consultar 16 shards; cooldown de 60 s, lease de 55 s y trabajo acotado a 45 s. Pagina los resultados y procesa lotes de 100; lo no completado queda indexado para otra ejecución. La recuperación no ejecuta Scan y no depende de TTL. Las lecturas/reasignaciones pueden materializar vencimientos, sin extenderlos. SQS/cron no garantizan ejecución exacta al segundo.

## DLQ y replay

Hay cuatro colas principales y cuatro DLQ de consumo: `inventory-commands`, `inventory-expirations`, `order-events`, `catalog-events`, con sufijo `-dlq` en local. AWS añade el prefijo de entorno y una quinta DLQ **de entrega del Scheduler**. La cola de expiraciones es independiente de compras. Visibilidad normal 60 s, long poll 20 s, redrive tras cinco recepciones, retención principal cuatro días y DLQ catorce días.

1. Registrar cola origen, `messageId` del envelope, tipo/schema, correlationId, número de recepciones y error class. El receipt handle es temporal; no es una clave de negocio. No copiar payload con identidad/motivo a logs públicos.
2. Corregir dependencia, permisos o incompatibilidad del consumer. Un contrato inválido no se arregla con replays repetidos.
3. Elegir muestra acotada y preservar **todo el envelope de aplicación**, IDs, versiones, requestedAt y expiresAt. El transporte puede asignar otro ID SQS; no debe cambiar el ID estable del envelope.
4. En AWS, usar el redrive administrado de la DLQ a su cola origen solo con permisos/autorización operativa y velocidad limitada. Confirmar compatibilidad KMS y observar errores. No se ha verificado redrive AWS en esta entrega.
5. ElasticMQ no se presume equivalente a todas las APIs AWS de redrive. Para un replay manual local, recibir un mensaje, enviar su body sin modificar a la cola origen y borrar el receipt de la DLQ **solo después** de publicación confirmada. La recepción cambia visibilidad; guardar el original en un archivo privado fuera de Git antes de intervenir.
6. Verificar que el efecto se aplicó una vez o fue un no-op legítimo; un comando vencido debe rechazar/liberar, nunca conceder otro plazo. No “solucionar” el rechazo creando IDs nuevos en un mensaje antiguo.

La DLQ de Scheduler diagnostica fallos de entrega a SQS, no fallos de negocio de Inventory. Examinar su formato y atributos antes de reenvío; no asumir que su body es idéntico a un envelope de una DLQ de consumo. El siguiente tick válido puede recuperar el trabajo indexado; un tick no representa una reserva individual.

## Observabilidad local y Sonar

```powershell
python scripts/local/setup_observability.py
docker compose -f docker-compose.yml -f compose.observability.yaml --profile observability up -d --wait
```

Grafana: `http://localhost:13000`; Prometheus: `http://localhost:19090`; Tempo: `http://localhost:13200`; health del collector: `http://localhost:13133`. El setup genera contraseña local en `.env` ignorado; no imprimirla ni añadirla a evidencia. El overlay activa exportación de trazas y sampling 1.0. Mantener siempre ambos `-f` al operar este perfil. Salud HTTP 200 no demuestra ingestión: comprobar targets, métricas con muestras y una traza real. [Observabilidad](observability.md) registra el probe local real y su alcance `dirty=true`; no acredita el candidato final ni AWS. Trazas HTTP no prueban por sí solas propagación del contexto a través de SQS.

Métricas disponibles: HTTP/JVM/SDK, `outbox_age_seconds` y `expiration_lag_seconds` emitidas por trabajo observado. Ausencia de un log de expiración no equivale a lag cero ni constituye una señal periódica de vida. Revisar también cola, error rate y salud. El exporter de logs OTLP está deshabilitado; logs estructurados van a stdout. No habilitar wire logging SDK ni dump de cuerpos para diagnosticar.

```powershell
python scripts/local/setup_quality.py
docker compose --profile quality up -d sonar
```

Sonar local está en `http://localhost:19000`. Configurar proyectos/gates con [sonar_setup.py](../scripts/quality/sonar_setup.py) y credenciales locales de alcance mínimo. [sonar_develop.py](../scripts/quality/sonar_develop.py) requiere un push confiable a develop, SHA coincidente, token de lectura y tokens de análisis; no falsificar variables de CI para presentar un análisis ajeno como gate del candidato. Esperar el análisis concreto y conservar ceTaskId/analysisId/SHA. No ejecutar dos Gradle sobre el mismo build directory simultáneamente. Detener el perfil quality antes de cargas que no tengan presupuesto de memoria para ambos.

## AWS, rollback y escalamiento de incidente

El repositorio [ticketing-infra](../../ticketing-infra/README.md) posee módulos, ambientes, IAM, Scheduler, alarmas, presupuestos y runbooks de [operación](../../ticketing-infra/docs/operations.md) y [despliegue](../../ticketing-infra/docs/deployment.md). No ejecutar apply como parte del arranque local ni crear recursos para “validar” un documento. Un despliegue necesita cuenta, región, identidad, presupuesto y autorización concretos; no se afirma gratuidad de ECS, endpoints o KMS.

Para rollback, elegir el digest previamente aprobado y compatible con datos/mensajes ya escritos. Mantener outbox, inbox, versiones y deadlines; rollback del contenedor no es rollback de ventas. No restaurar una tabla antigua sobre el estado activo para deshacer SOLD/COMPLIMENTARY. Si el fallo afecta integridad, detener nuevas escrituras y preservar evidencia antes de una reparación diseñada y revisada. Registrar impacto, ventana, IDs opacos, versión de imagen, acción y validación; excluir tokens y PII de reportes compartidos.
