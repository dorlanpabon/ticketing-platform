# Resultados de la prueba

El 16 de noviembre de 2026 se construyó la aplicación desde un clon limpio de `develop` en `b9392f2`, con Java 25, DynamoDB Local y LocalStack SQS 4.14.0. La revisión equivalente en el historial actual es `d77f673`. La [guía de operación](operacion.md) indica cómo repetir las pruebas.

| Requisito | Resultado observado |
| --- | --- |
| RF1 · Eventos | Se creó un evento, se aprovisionaron sus entradas y pasó a `ACTIVE`. |
| RF2 · Reserva de hasta diez minutos | Se esperaron 600 segundos. DynamoDB registró la liberación 0,30 s después del vencimiento. Una confirmación tardía devolvió `410` y otro comprador volvió a reservar la entrada. |
| RF3 · Compra asíncrona | `202` tras la confirmación de SQS. Al pausar la cola, la API respondió `503`. Tras reanudarla, la misma clave recuperó el ID, el plazo y el mensaje originales. |
| RF4 · Consulta de orden | El comprador consultó su orden. Otro comprador recibió `404`. |
| RF5 · Concurrencia | 100 compras disputaron una entrada con hasta 40 solicitudes en paralelo. Hubo una reserva, 99 rechazos y un reintento de envío. |
| RF6 · Liberación automática | El mensaje diferido liberó la reserva con el barrido periódico y la API de Inventory detenidos. La prueba de integración también verificó el barrido de vencidos. La entrada vendida de control conservó `SOLD`. |
| RF7 · Disponibilidad | Se verificaron los contadores después de reservar, confirmar y asignar una cortesía. |

| Servicio | Unitarias | Integración | Líneas | Ramas |
| --- | ---: | ---: | ---: | ---: |
| Catalog | 79 | — | 98,26 % | 94,59 % |
| Inventory | 85 | 12 | 96,23 % | 92,86 % |
| Orders | 107 | 6 | 96,86 % | 90,20 % |

Pasaron **271 pruebas unitarias y 18 de integración**, PMD, Gitleaks, Checkov y Trivy. DynamoDB conservó el registro de prueba al reiniciar. LocalStack recreó sus ocho colas y la compra pasó de nuevo.

La [colección en español](../contracts/ticketing.postman_collection.json) pasó con **40 solicitudes y 48 aserciones**. La colección tiene doce pasos y repite consultas mientras espera cambios de estado. Los ejemplos del README también pasaron con 17 entradas disponibles, 2 vendidas y 1 de cortesía.

IaC se verificó en `80d0252` con **14 pruebas simuladas de Terraform y 7 de Python**. La evidencia es local. El despliegue en AWS, la carga sostenida y Sonar quedan como verificaciones adicionales.
