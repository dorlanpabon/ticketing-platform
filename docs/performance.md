# Rendimiento: método, escenarios y evidencia

Este documento define cómo medir la implementación local. La existencia de scripts y una validación estática de k6 no prueban throughput, percentiles, cero sobreventa bajo carga ni cumplimiento del objetivo de mil usuarios durante treinta minutos. Cada ejecución produce evidencia separada y conserva sus fallos.

## Perfiles y límites

| Perfil | VUs configurados | Ventana | Presupuesto de pacing | Dataset predeterminado | Generador |
|---|---:|---:|---:|---:|---|
| smoke | 50 | 30 s | 20 requests/s | 1 evento de 2000 entradas; otro de 1 cuando aplica | 1 CPU, 512 MiB |
| full | 1000 | 1800 s | 100 requests/s | 1 evento de 100000 entradas; otro de 1 cuando aplica | 2 CPU, 1536 MiB |
| cohort | 100 o 1000 | Un intento por VU; máximo 120 s | Ráfaga explícita, sin pacing | Un evento de 1 entrada | Límites configurados del perfil |

`smoke` es el predeterminado. `full` requiere `PERF_FULL=1`; la ráfaga `cohort` requiere `PERF_STRESS=1`. Los límites máximos del runner son 1000 VUs, 1800 s, 1000 requests/s de presupuesto y 4 GiB/4 CPU para el generador. El modo sin argumento es `plan`: no ejecuta carga.

La carga normal es closed-loop: cada VU espera sus respuestas y aplica un intervalo de petición `VUs / MAX_RPS`, con arranque disperso. Se limita la frecuencia por VU; no es un token bucket global y las ventanas breves pueden variar por scheduling. Un VU también espera/pollea: **1000 VUs no significan 1000 peticiones HTTP simultáneas**. Se informan los VUs y requests reales de k6; no se inventa una métrica global de requests en vuelo.

Se alternan las dos identidades de cliente locales entre VUs. Se simulan sesiones concurrentes, no mil usuarios Cognito ni mil verificaciones JWT reales. Las URLs y red pertenecen exclusivamente al Compose local; el runner no acepta una URL AWS remota.

## Escenarios

| Nombre | Operaciones | Validación |
|---|---|---|
| read | Obtener evento, paginar entradas y consultar disponibilidad. | HTTP esperado e invariante de contadores en respuestas de disponibilidad. |
| distributed | Comprar entradas distintas, observar reserva, confirmar y observar SOLD. | Selección sin reutilización entre VUs; agotamiento del dataset falla explícitamente. |
| hotseat | Competir reiteradamente por una entrada y confirmar al ganador. | Exactamente una venta observada; rechazos se contabilizan por separado. |
| mixed | Aproximadamente 80 % lecturas, 15 % compras distribuidas y 5 % competencia por una entrada. | Señales técnicas, de negocio, async e invariantes separadas. |
| cohort | Una compra por cada VU, sincronizada mediante hora de inicio; no confirma ni vuelve a comprar. | Audita todos los pedidos y la disponibilidad autoritativa: exactamente una reserva, resto rechazado y ningún resultado pendiente. |

La distribución usa semilla `20261116` por defecto; `--seed` cambia el patrón de operaciones y el desplazamiento de asientos. Cada VU posee una secuencia exclusiva de asientos para distributed. Cambiar VUs, semilla, capacidad o cantidad de eventos cambia el experimento y queda registrado. La semilla no hace determinista el scheduling de red ni los UUID generados por la aplicación.

Preparación crea datos nuevos mediante la API, no escribiendo directamente en DynamoDB. Espera que cada evento esté ACTIVE, con máximo 300 s por evento; no inicia la carga sobre inventario incompleto. No reutiliza un evento vendido como si estuviera vacío. `--events` y `--capacity` permiten distribuir compras entre varios eventos. Los datos quedan para auditoría; no se borran tablas ni volúmenes automáticamente.

## Comandos

Primero terminar las pruebas de aceptación/expiración y restaurar APIs/workers. Detener el perfil opcional quality y revisar memoria. El runner rechaza Sonar activo y rechaza que la suma de límites de memoria de servicios y generador supere el 90 % de la VM Docker. No cambia límites ni detiene servicios para lograr un resultado.

```powershell
python scripts/performance/run.py plan
python scripts/performance/test_run.py
python scripts/performance/run.py validate

python scripts/performance/run.py run --scenario read
python scripts/performance/run.py run --scenario distributed
python scripts/performance/run.py run --scenario hotseat
python scripts/performance/run.py run --scenario mixed

$env:PERF_STRESS = '1'
python scripts/performance/run.py run --scenario cohort --vus 100
python scripts/performance/run.py run --scenario cohort --vus 1000 --generator-memory-mib 1536 --generator-cpus 2
Remove-Item Env:PERF_STRESS

$env:PERF_FULL = '1'
python scripts/performance/run.py plan --profile full --scenario mixed
python scripts/performance/run.py run --profile full --scenario mixed
Remove-Item Env:PERF_FULL
```

Las variantes `read`, `distributed` y `hotseat` también admiten `--profile full`. No ejecutar todas simultáneamente. El runner limita duración, CPU y memoria del generador y conserva exit code/OOM. En una VM de 8 GiB, un experimento de mil VUs puede no completar o no alcanzar los objetivos; ese resultado se registra como fallo o incompleto, sin convertirlo en éxito por cambiar el umbral después.

## Umbrales e interpretación

| Señal | Objetivo inicial |
|---|---|
| Aceptación HTTP de orden | p95 <500 ms. Un 202 solo prueba aceptación durable. |
| Consulta HTTP de disponibilidad | p95 <300 ms en read/mixed. |
| Confirmación aceptada → SOLD observado | p95 <5000 ms en escenarios con confirmación. |
| Respuestas técnicas inesperadas | <1 %. HTTP 409/410 de negocio se separan. |
| Respuesta inválida, resultado async sin resolver o dataset agotado | Cero. |
| Invariante de inventario | Cero violaciones en muestras y auditoría posterior. |
| Cohort | Exactamente un ganador y todos los demás intentos resueltos/rechazados. |

El presupuesto de pacing también aplica a las consultas de seguimiento. Con 1000 VUs y 100 requests/s, el intervalo por VU es de 10 s: la latencia de SOLD observado incluye esa espera y no permite atribuir todo el retraso al backend. Puede fallar el objetivo de 5 s aun si el cambio durable ocurrió antes. Ese dato se conserva; para aislar latencia backend hace falta un observador independiente o trazas correlacionadas, y otro experimento con presupuesto explícito. No se descuentan tiempos de forma especulativa.

Los 202 que después se rechazan por inventario no se suman como error técnico. Se reportan `business_rejections` por fase y `async_unresolved` cuando no se conoce el resultado a tiempo. La confirmación medida no equivale a un pago externo; esta prueba no integra una pasarela de pagos.

La auditoría general comprueba `capacity = available + reserved + pendingConfirmation + sold + complimentary` y valores no negativos. Esa invariante es necesaria, pero por sí sola no demuestra unicidad de cada comprador. La cohorte añade auditoría de todos sus pedidos, un ganador y disponibilidad autoritativa; la suite de aceptación complementa la evidencia de transacciones, idempotencia y concurrencia.

## Artefactos por ejecución

Los resultados se escriben en `evidence/local/performance/<fecha-UTC>-<id>/`, excluidos de Git:

- `report.json`: configuración efectiva, versión/digest k6, SHA Git y working tree sucio/limpio, hashes de script/Compose/dataset, fechas, exit code, umbrales fallidos, auditoría y estado del generador.
- `dataset.json`: IDs reales de eventos, capacidad, semilla y hora coordinada de cohorte. Nunca contiene contraseñas o tokens Cognito.
- `summary.json`: métricas originales de k6, incluidos percentiles y conteos. No se sustituyen percentiles sin observaciones por cero; una ejecución sin requests o iteraciones completadas no pasa.
- `resources.jsonl`: muestras de CPU, memoria, red, disco y PIDs reportadas por Docker cada aproximadamente cinco segundos; límites e image IDs se registran antes/después.
- `runner.log`: salida real del generador. En cohort, `cohort-attempts.jsonl` y `cohort-audit.json` conservan intentos, rechazos HTTP/asíncronos, ganador, pendientes y dispersión real de inicio.

Los recursos del generador forman parte del resultado: saturarlo puede limitar la carga. Una ejecución con working tree sucio identifica las imágenes locales reales y no se presenta como validación de un commit cerrado. `validate` produce únicamente `configuration-validated-without-application-traffic`, nunca un benchmark aprobado.

Para el informe final, adjuntar recursos, escenario, seed, dataset, duración real, VUs, requests/s, p95/p99, errores técnicos, rechazos de negocio, pendientes, invariantes, OOM y evolución de memoria. Verificar una ventana sostenida y la pendiente de memoria con las muestras; un máximo aislado no demuestra estabilidad ni fuga. Las mediciones de DynamoDB Local/ElasticMQ no prueban capacidad, costo, IAM ni rendimiento AWS.

El runner deja `memoryStabilityAssessed=false`: su estado `passed` indica umbrales k6 y auditoría funcional aprobados, no una certificación automática de estabilidad de memoria. Esa evaluación requiere analizar la serie temporal y documentar el criterio usado.
