# Operación local

Ejecuta los comandos desde la raíz de `ticketing-platform`. El [README](../README.md) reúne los requisitos, la configuración y los ejemplos de la API.

## Arrancar y consultar registros

```powershell
docker compose config --quiet
docker compose up --build -d --wait --scale inventory-worker=2
docker compose ps
docker compose logs -f --tail 100 catalog-worker orders orders-worker inventory-worker expiry-recovery localstack
```

Usa `Ctrl+C` para dejar de seguir los registros. Si arrancaste Compose con `-p`, usa ese mismo nombre en los comandos y configura `COMPOSE_PROJECT_NAME` antes de ejecutar los scripts. Para identificar un puerto ocupado, consulta `docker compose ls` y `docker ps`.

## Probar la colección

Importa el [JSON de Postman](../contracts/ticketing.postman_collection.json), abre **Ticketing — aceptación local** y selecciona **No Environment**. Usa **Run collection** desde **01 Crear evento**, con un intervalo de **500 ms**. El resultado final esperado es 17 entradas disponibles, dos vendidas y una de cortesía.

| Paso | Resultado esperado |
|---|---|
| **08 Rechazar consulta de otro comprador** | `404 ORDER_NOT_FOUND`, porque la orden pertenece a otra identidad |
| **12 Rechazar cambios con la misma clave** | `409 IDEMPOTENCY_CONFLICT`, porque la clave original se reutilizó con otro contenido |

Consulta **Console** en Postman para ver las peticiones y respuestas. Si pruebas a mano, envía los pasos en orden y repite la consulta de espera hasta que la API devuelva el estado indicado.

## Repetir la aceptación

Ejecuta con Python 3, los servicios listos y dos réplicas de `inventory-worker`. El control `architecture` usa JDK 25 en el equipo. La prueba de expiración y las de recuperación interrumpen componentes del entorno, así que ejecútalas en secuencia y sin otras pruebas en curso.

```powershell
python scripts/acceptance/verify.py --checks containerSmoke integration concurrency architecture --output build/quality/acceptance.json
python scripts/acceptance/verify.py --checks expiry --output build/quality/expiry.json
python scripts/acceptance/verify.py --checks queueOutage recovery --output build/quality/recovery.json
```

`expiry` espera 600 segundos reales y verifica la liberación sin la API de Inventario ni el emisor periódico durante la observación. `queueOutage` pausa LocalStack y comprueba que el mismo reintento puede recuperar la orden. `recovery` detiene y restaura componentes de Inventario. Usa un árbol Git limpio para guardar evidencia asociada a un commit. Para una comprobación de desarrollo con cambios locales, el script admite `--allow-dirty`.

## Recuperar el entorno

Si una prueba se interrumpe, comprueba `docker compose ps`. Si LocalStack aparece como `Paused`, reanúdalo con `docker compose unpause localstack`. Después restaura los servicios:

```powershell
docker compose up -d --wait --scale inventory-worker=2
docker compose ps
```

Ante `503 ORDER_ENQUEUE_UNAVAILABLE`, reenvía el mismo cuerpo con la misma `Idempotency-Key`. Si la API devuelve `410 RESERVATION_EXPIRED`, el plazo original terminó y una compra nueva requiere otra clave.

Usa `docker compose stop` para detener el entorno y conservar sus volúmenes. DynamoDB Local conserva los registros en su volumen y LocalStack Community recrea las colas vacías al reiniciar.
