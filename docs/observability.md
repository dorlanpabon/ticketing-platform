# Observabilidad local y AWS

El perfil opcional agrega Prometheus 3.16.0, Grafana 13.3.1, Tempo 3.0.0 y OpenTelemetry Collector Contrib 0.165.0. Las cuatro imágenes están fijadas por digest. Spring Boot 4.1.2 utiliza su starter oficial OpenTelemetry; los exportadores de trazas están desactivados por defecto y se habilitan mediante el archivo Compose adicional.

```sh
python scripts/local/setup_observability.py
docker compose -f compose.yaml -f compose.observability.yaml --profile observability up -d --build --scale inventory-worker=2 --wait
```

La contraseña aleatoria de Grafana se guarda en `.env`, ignorado por Git. Usuario: `admin`. No publicar ese archivo ni sus valores. El script conserva las variables existentes de Sonar. El perfil agrega hasta 1.35 GiB de límites de memoria; detener Sonar antes de usarlo junto con la aplicación en una VM Docker de 8 GiB.

| Componente | Acceso local | Uso |
|---|---|---|
| Grafana | http://localhost:13000 | Dashboard `Ticketing runtime`; Explore para Prometheus y Tempo. |
| Prometheus | http://localhost:19090 | Targets, salud de scrapes, métricas HTTP/JVM/SDK. |
| Tempo | http://localhost:13200 | API de consulta de trazas; `/ready`. |
| Collector | http://localhost:13133 | Readiness; los puertos OTLP permanecen dentro de la red Docker. |

Las interfaces expuestas enlazan exclusivamente a `127.0.0.1`. Grafana requiere autenticación y no permite registro ni acceso anónimo. Los datos permanecen en volúmenes locales. Prometheus y Tempo tienen retención de 24 horas. La configuración desactiva telemetría de uso y preinstalación automática de plugins de Grafana.

Prometheus descubre todas las réplicas de workers mediante DNS de Compose. El dashboard distingue aplicación y rol y muestra tráfico HTTP, p95, heap, CPU y salud de targets. No inferir disponibilidad funcional únicamente de `UP`: también se verifican invariantes y recuperación con la suite de aceptación.

El perfil local muestrea el 100% de las trazas para la demostración; fuera del overlay la probabilidad configurada es 0.1 y la exportación está apagada. Los límites de conexión y exportación evitan que un backend de trazas caído sea una dependencia crítica de negocio. Las trazas HTTP usan Micrometer y el `WebClient.Builder` autoconfigurado. Los mensajes asíncronos conservan `messageId`, `correlationId` y `causationId`; eso permite correlación de negocio, pero no se presenta como propagación W3C completa a través del outbox y SQS.

Los logs ECS emiten campos estructurados `metric` y `value` para `outbox_age_seconds` y `expiration_lag_seconds`. El primer valor mide la edad del mensaje al intentar publicarlo; no prueba que no exista backlog cuando faltan muestras. La expiración también registra `operation=ExpireReservation`, el identificador opaco de reserva y el resultado. No se registran tokens, credenciales, cuerpos de solicitudes ni identidad del comprador.

## Verificación real local

El [probe reproducible](../scripts/local/verify_observability.py) genera tráfico de negocio: crea un evento aislado, espera ACTIVE y acepta una orden con `traceparent` W3C. Después comprueba trazas nuevas en Tempo, la relación padre-hijo entre el span cliente de Orders y el span servidor de Inventory, targets Prometheus y las consultas de los cinco paneles provisionados de Grafana. No ejecutarlo durante expiración, caos, persistencia o carga sobre el mismo entorno.

```powershell
python scripts/local/verify_observability.py --expected-workers 4 --timeout 120 --output build/quality/observability-dev.json
```

Cuatro workers significan uno Catalog, uno Orders y dos Inventory; el probe espera además tres targets API. El timeout se aplica a las esperas acotadas de cada comprobación. Credenciales de Grafana se leen de la configuración local y no se incluyen en el reporte.

Resultado observado en [observability-dev.json](../build/quality/observability-dev.json), 2026-11-16 entre **09:54:22 y 09:54:33 UTC**:

| Comprobación | Evidencia observada |
|---|---|
| Prometheus | Tres targets API y cuatro workers con `up=1`. |
| Catalog | Span servidor de la solicitud HTTP de negocio nueva, con padre W3C recibido. |
| Orders → Inventory | Mismo traceId y relación padre-hijo comprobada entre span cliente Orders y servidor Inventory; incluye el span servidor Orders. |
| Grafana | Dashboard `ticketing-runtime` provisionado; cinco paneles con series numéricas finitas y fuentes Prometheus/Tempo presentes. |
| Alcance Git | HEAD `8c3e780ef2146c1f766997541d19aba431b5f048`, `dirty=true`, mismo HEAD al inicio y al final. |

El probe terminó `passed`, pero el árbol sucio impide atribuir el resultado a un candidato cerrado. El reporte generado se conserva como artefacto de desarrollo y no necesariamente existe en un clon nuevo. No demuestra propagación de contexto por SQS (`sqsPropagationVerified=false`), alarmas/ingestión AWS, estabilidad de memoria, un objetivo p95 bajo carga ni ausencia exhaustiva de datos sensibles en todas las bibliotecas. La existencia de series en el panel p95 demuestra que la consulta produce datos; no que se cumpla el objetivo de rendimiento. Actualizar evidencia del artefacto final por separado, sin convertir esta prueba en aprobación global.

## Frontera AWS

En AWS los módulos del repositorio `ticketing-infra` configuran CloudWatch Logs, filtros de métricas, alarmas de colas/DLQ y escalado. Este overlay local no despliega Grafana/Tempo en AWS. La configuración de alarmas y sus permisos solo se consideran probados contra AWS después de un despliegue y una prueba real; los tests Terraform locales utilizan proveedores mock.

Fuentes verificadas: [Spring Boot 4.1.2 tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html), [ejemplo oficial de Tempo 3](https://github.com/grafana/tempo/blob/v3.0.0/example/docker-compose/single-binary/tempo.yaml). Los nombres de propiedades Spring y flags de retención Tempo también se contrastaron con los metadatos y `-help` de los binarios fijados.
