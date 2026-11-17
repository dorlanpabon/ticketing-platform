# Probar la API con Postman

La [colección de Postman](../../contracts/ticketing.postman_collection.json) ejecuta doce pasos sobre Docker Compose. Crea y activa un evento de veinte entradas, reserva dos, repite la compra con la misma clave, confirma la venta, verifica la autorización por propietario, asigna una cortesía y comprueba la disponibilidad.

1. Levanta el entorno según el [README de la aplicación](../../README.md).
2. En Postman, pulsa **Import**, selecciona el archivo JSON y abre **Ticketing — aceptación local**.
3. Selecciona **No Environment** para usar las variables de la colección. Los puertos locales son `18080` para Catálogo, `18081` para Órdenes y `18082` para Inventario.
4. Ejecuta **Run collection** desde el primer paso, con **500 ms** entre solicitudes. Cada ejecución genera un `runId` y guarda sus propios identificadores. Si ejecutas a mano, respeta el orden y repite las consultas de espera hasta observar el estado indicado.

El resultado esperado tras la venta y la cortesía es de 17 entradas disponibles, dos vendidas, una de cortesía y cero reservadas. El paso **08** espera `404 ORDER_NOT_FOUND` al consultar la orden como otro comprador. El paso **12** espera `409 IDEMPOTENCY_CONFLICT` al reutilizar la clave de compra con un contenido distinto.

## Ejecución opcional con Newman

Se usa Node.js 24 LTS y una instalación externa de Newman `6.2.3` con su `package-lock.json`. Para validar la estructura de la colección y el manejo de reportes, ejecuta desde la raíz:

```powershell
node --test scripts/postman/run.test.cjs
node scripts/postman/run.cjs validate
```

Estas comprobaciones no envían solicitudes a la aplicación. Para ejecutar el flujo HTTP, utiliza una instalación previamente revisada y ajusta la ruta del ejemplo:

```powershell
docker compose up -d --wait --scale inventory-worker=2
$env:NEWMAN_MODULE = (Resolve-Path '../postman-tooling/node_modules/newman').Path
node scripts/postman/run.cjs run
```

`NEWMAN_MODULE` debe apuntar a un directorio absoluto fuera del repositorio. Si usas la opción `-p` al iniciar Compose, define `COMPOSE_PROJECT_NAME` con ese mismo nombre al ejecutar el script. El ejecutor comprueba la versión y el archivo de bloqueo, limita las peticiones a los puertos locales y requiere las API y los trabajadores saludables, con dos réplicas de Inventario. Usa identidades de prueba y desactiva las redirecciones. El intervalo es de 500 ms, el límite de cada solicitud es de 15 s y la ejecución completa tiene un máximo de seis minutos. El proceso termina con un código distinto de cero si falta una etapa, falla una aserción o se produce un error de transporte.

La auditoría de la instalación examinada encontró vulnerabilidades en dependencias transitivas, incluidas [GHSA-qxc2-j82w-r537](https://github.com/advisories/GHSA-qxc2-j82w-r537) y [GHSA-2w6w-674q-4c4q](https://github.com/advisories/GHSA-2w6w-674q-4c4q). El ejecutor registra ese resultado en `dependencyAuditStatus`. Para la demostración, basta con importar el JSON en Postman.

## Resultados y bitácoras

El ejecutor guarda un reporte local en `evidence/local/postman/<UTC>-<uuid>/report.json`. Incluye los códigos HTTP, los tiempos, las aserciones, los identificadores de prueba, el commit y las imágenes ejecutadas. Excluye cuerpos, cabeceras, cookies, credenciales y mensajes crudos de error. Los reportes se generan al ejecutar la colección y esa ruta está excluida de Git.

`status: passed` indica que la colección completó sus aserciones. La prueba de vencimiento de diez minutos, la concurrencia y la recuperación se verifican con el [ejecutor de aceptación](../acceptance/verify.py). Para seguir la actividad del entorno Docker, consulta la [guía de operación](../../docs/operacion.md).
