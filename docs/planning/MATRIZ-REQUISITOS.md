# Matriz de requisitos y evidencias

**Estado del documento: planificación.** Esta matriz define la trazabilidad que deberá cumplir la entrega. No acredita código implementado, pruebas ejecutadas, cobertura alcanzada, infraestructura desplegada ni resultados de Sonar.

**Fuente primaria:** `Prueba 2026 desarrolladores (2).pdf`, ocho páginas, suministrado en `D:/Usuario/Descargas/Prueba 2026 desarrolladores (2).pdf`. Las páginas indicadas son las posiciones del PDF, comenzando por 1.

**Fuentes adicionales de alcance:** decisiones de alcance del proyecto. Los requisitos del PDF, las ampliaciones solicitadas y los valores elegidos para el diseño se distinguen en las tablas.

## 1. Cómo utilizar la matriz

- Cada fila permanecerá **PENDIENTE** hasta contar con una evidencia verificable del requisito completo.
- Una evidencia deberá identificar commit, comando o procedimiento, resultado y ubicación del reporte. Si corresponde, añadirá imagen por digest, configuración, ambiente y fecha de ejecución.
- Los resultados deberán clasificarse como **unitarios**, **integración local**, **análisis estático** o **AWS real**. Un resultado local no acredita ejecución real en AWS.
- Un documento de diseño acredita que una decisión está especificada; no acredita que su comportamiento ya esté implementado.
- La aceptación final exige revisar tanto el requisito como sus casos negativos y de recuperación. Una captura de un flujo feliz no basta para demostrar concurrencia o atomicidad.

## 2. Requisitos funcionales del PDF

| ID | Origen / página | Requisito | Implementación planeada | Prueba o evidencia planeada | Estado |
|---|---|---|---|---|---|
| F01 | PDF, p. 3, RF1 | Crear y consultar eventos con nombre, fecha, lugar y capacidad total; mantener su inventario. | Catálogo administra metadatos; Inventario prepara entradas numeradas de manera idempotente. Evento `PROVISIONING` hasta completar preparación y después `ACTIVE`. | Crear, consultar y listar; validar campos; interrumpir preparación y reanudar; comprobar que la suma de entradas coincide con capacidad y que no se vende durante preparación parcial. | PENDIENTE |
| F02 | PDF, p. 3, RF2 | Reservar temporalmente las entradas al iniciar una compra, por un máximo de diez minutos; devolverlas si no se confirma. | Intención durable en Órdenes, intento de reserva breve y recuperación asíncrona idempotente; Inventario controla un deadline fijo de hasta 600 segundos desde la aceptación inicial. La respuesta `202` no afirma reserva exitosa. | Verificar exclusividad de entradas reservadas, plazo inmutable y casos antes, en y después del deadline. Incluir la interpretación A01 en contrato y sustentación. | PENDIENTE |
| F03 | PDF, p. 3, RF3 | Encolar solicitudes inmediatamente, retornar identificador y procesar compras mediante consumidor que valida disponibilidad y actualiza inventario y orden. | Órdenes persiste solicitud y outbox en una transacción local. El relay publica `PurchaseRequested` en SQS; Inventario valida y aplica el resultado; Órdenes recibe un snapshot versionado. | Detener y reanudar consumidor; verificar orden recuperable; probar cola indisponible y recuperación de outbox; medir retraso de publicación y distinguir persistencia del outbox de recepción efectiva por SQS. | PENDIENTE |
| F04 | PDF, p. 3, RF4 | Consultar la orden por ID con los cinco estados indicados. | API de Órdenes verifica propietario y consulta la reserva autoritativa en Inventario. Expone `status`, `workflowStatus`, `resolution`, versión y asignación activa. | Consultar cada estado; casos `RESERVING`, rechazo y expiración; inventario inaccesible devuelve error explícito, sin presentar una proyección atrasada como estado actual. | PENDIENTE |
| F05 | PDF, p. 4, RF5 | Manejar condiciones de carrera y evitar sobreventa ante solicitudes concurrentes del mismo evento. | Transacciones locales y condiciones sobre estado, versión y reserva. Reserva de varias entradas completa o nula. | Cien compradores por una entrada y mil intentos contra cien entradas; verificar en persistencia que no hay doble asignación, cantidades negativas ni reservas parciales. | PENDIENTE |
| F06 | PDF, p. 4, RF6 | Identificar y liberar automáticamente reservas expiradas mediante proceso periódico. | Worker por registros de vencimiento, sin `Scan`; ejecución cada segundo; liberación transaccional de toda la reserva. Reasignación recupera primero la reserva anterior vencida. | Dos expiradores concurrentes, repetición, reinicio, atraso y caída del worker; confirmar que cada reserva se libera una sola vez y nunca se libera una venta. | PENDIENTE |
| F07 | PDF, p. 4, RF7 | Consultar disponibilidad actual mediante endpoint reactivo considerando ventas y reservas. | Snapshot transaccional de los dieciséis contadores de Inventario; incluir los cinco estados y capacidad. Tratamiento explícito del retraso de liberación. | Verificar cantidades después de reservar, confirmar, expirar y otorgar cortesía; lecturas concurrentes; detección de atraso superior al objetivo. | PENDIENTE |

## 3. Reglas del dominio

| ID | Origen / página | Requisito | Implementación planeada | Prueba o evidencia planeada | Estado |
|---|---|---|---|---|---|
| D01 | PDF, p. 2 | Una entrada tiene exactamente un estado entre `AVAILABLE`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD` y `COMPLIMENTARY`. | Máquina de estados explícita; transiciones validadas en el dominio y condicionadas en persistencia. | Tabla de transiciones válidas e inválidas; secuencias generadas comprobando exclusividad de estado. | PENDIENTE |
| D02 | PDF, p. 2 | Transiciones atómicas y auditables. | Reserva, entradas, contadores, auditoría y outbox dentro de la misma transacción de Inventario. | Inyectar fallo antes del commit; comprobar ausencia de mutación parcial; verificar registro de actor, origen, destino, reserva, versión y correlación por transición aplicada. | PENDIENTE |
| D03 | PDF, p. 2 | `RESERVED` y `PENDING_CONFIRMATION` no son ventas. | Contadores diferenciados; solo `SOLD` incrementa cantidades vendidas. | Reservar y pasar a pendiente sin incrementar ventas; confirmar y observar un único incremento. | PENDIENTE |
| D04 | PDF, p. 2 | `SOLD` es final e irreversible. | Ninguna transición de salida; liberaciones y mensajes antiguos condicionados por reserva y versión. | Intentar expiración, liberación y replay de eventos anteriores contra una venta; conservar propietario y estado. | PENDIENTE |
| D05 | PDF, p. 2 | `COMPLIMENTARY` es final y no contable. | Flujo administrativo de cortesía; consume disponibilidad sin incrementar ventas. | Cortesía autorizada e idempotente; concurrencia con compra; rechazo de reversión y de actor sin permisos. | PENDIENTE |
| D06 | PDF, pp. 1–3, contexto y objetivo | Evitar compras duplicadas, asientos con varios propietarios y sobreventa. | Identidad de entrada estable, idempotencia HTTP y de mensajes, resultados terminales y condiciones de asignación. | Duplicados simultáneos, timeout tras commit y redelivery posterior; máximo un propietario activo por entrada. | PENDIENTE |

## 4. Stack y especificaciones técnicas

| ID | Origen / página | Requisito | Implementación planeada | Prueba o evidencia planeada | Estado |
|---|---|---|---|---|---|
| T01 | PDF, p. 4 | Java 25 y características modernas; Virtual Threads solamente si aplican. | Toolchain Java 25, records y modelado explícito de resultados. No incorporar Virtual Threads en el flujo de I/O asíncrono. | Build y contenedor con Java 25; ADR sobre programación reactiva y decisión de no usar Virtual Threads. | PENDIENTE |
| T02 | PDF, p. 4 | Spring Boot 4.x. | Versión 4.x fijada en los tres servicios y compatible con el scaffold. | Dependencias resueltas, build y arranque de los tres servicios. | PENDIENTE |
| T03 | PDF, pp. 3–5 | Spring WebFlux y operaciones no bloqueantes. | WebFlux, Reactor, `WebClient` y clientes asíncronos del AWS SDK v2. | Pruebas HTTP/reactivas y revisión de bloqueo; sin `.block()`, `.join()`, `Thread.sleep()` ni SDK síncrono en rutas productivas. | PENDIENTE |
| T04 | PDF, pp. 3–4 | Persistencia NoSQL para eventos, órdenes e inventario. | DynamoDB por propietario; DynamoDB Local para integración local. | Reinicio con datos persistentes y consultas por claves/índices; modelo y patrones de acceso documentados. | PENDIENTE |
| T05 | PDF, pp. 4–5 | Mensajería asíncrona; especificación expresa de SQS con entrega al menos una vez. | SQS Standard en AWS; ElasticMQ compatible con SQS en local; tres colas y DLQ por cola. | Redelivery tras pérdida de ACK, duplicados, visibilidad, errores transitorios y poison messages. La equivalencia completa con AWS no se presupone. | PENDIENTE |
| T06 | PDF, p. 4 | Docker para aplicación y dependencias. | Imágenes fijadas y ejecución por Compose; usuario no root y configuración externa. | Build y arranque desde clon limpio; healthchecks, reinicio y persistencia. | PENDIENTE |
| T07 | PDF, pp. 4–5 | Clean Architecture con dominio, casos de uso e infraestructura separados. | Estructura generada por scaffold en cada servicio; puertos y dependencias hacia el dominio. | `validateStructure`, ArchUnit y revisión de dependencias; dominio sin Spring ni AWS SDK. | PENDIENTE |
| T08 | PDF, p. 5 | API completamente reactiva con `Mono` y `Flux`. | Contratos reactivos y cadenas sin trabajo oculto ni buffers ilimitados. | `StepVerifier`, cancelación, propagación de errores, backpressure y pruebas HTTP. | PENDIENTE |
| T09 | PDF, p. 5 | Manejo reactivo de errores y retries apropiados. | Clasificación de errores; presupuesto único de retries transitorios con backoff y jitter; conflictos de negocio no reintentados como fallas técnicas. | Fallos transitorios y permanentes, agotamiento de retries y ausencia de multiplicación entre SDK y Reactor. | PENDIENTE |
| T10 | PDF, p. 5 | Optimistic locking o conditional writes para inventario. | Condiciones de estado, versión y `reservationId` dentro de transacciones DynamoDB. | Pruebas concurrentes sobre persistencia real local; no usar locks en memoria como garantía distribuida. | PENDIENTE |
| T11 | PDF, pp. 5–6 | `docker-compose.yml` con aplicación y todas sus dependencias. | Tres servicios, DynamoDB Local, cola persistente, bootstrap idempotente e identidades locales. Observabilidad y Sonar como perfiles adicionales. | `docker compose up --build --wait` desde clon limpio, seguido de colección de aceptación. | PENDIENTE |

## 5. Entregables obligatorios

| ID | Origen / página | Requisito | Implementación planeada | Prueba o evidencia planeada | Estado |
|---|---|---|---|---|---|
| E01 | PDF, p. 5 | Repositorio fuente con estructura Clean Architecture. | Monorepo con tres proyectos generados, contratos y configuración versionada. | Revisión del árbol, instrucciones de build y enlace al commit de entrega. | PENDIENTE |
| E02 | PDF, p. 5 | Variables, clases, métodos y comentarios en inglés. | Convención explícita para código propio y revisión de PR. | Revisión de cambios y muestreo de nombres/comentarios antes de entrega. | PENDIENTE |
| E03 | PDF, p. 5 | SOLID y patrones apropiados. | Puertos, adaptadores, responsabilidades delimitadas, saga, outbox e idempotencia con justificación concreta. | ADR, pruebas arquitectónicas y explicación de los costos de cada patrón. | PENDIENTE |
| E04 | PDF, pp. 5–6 | README con descripción, instalación, configuración, Docker, decisiones y ejemplos. | Guía de inicio reproducible con requisitos, comandos exactos y enlaces a documentación ampliada. | Una persona o ejecución limpia sigue únicamente el README hasta completar el flujo principal. | PENDIENTE |
| E05 | PDF, p. 6 | Unitarios con cobertura mínima del 90 %. | JaCoCo unitario por servicio; umbral elegido de al menos 90 % de líneas y ramas del código propio. Integración se reporta separadamente. | Reportes XML/HTML y gate fallido cuando se reduce cobertura; no sumar integración para ocultar déficit unitario. | PENDIENTE |
| E06 | PDF, p. 6 | Pruebas de casos de uso con mocks de repositorios. | JUnit Jupiter, Mockito y puertos de dominio simulados. | Casos felices, conflictos, excepciones, idempotencia y tiempo controlado. | PENDIENTE |
| E07 | PDF, p. 6 | Pruebas de componentes reactivos WebFlux y servicios. | `WebTestClient`, `StepVerifier` y `Clock` inyectable. | Validación de contratos, errores, autorización, cancelación y temporización. | PENDIENTE |
| E08 | PDF, p. 6 | Pruebas que verifiquen concurrencia. | Integración contra DynamoDB Local y cola; ejecuciones simultáneas reales. | Última entrada, compra múltiple, confirmación frente a expiración y consumidores duplicados. | PENDIENTE |
| E09 | PDF, p. 6 | Usar frameworks como JUnit 5, Mockito y reactor-test. | JUnit Jupiter compatible con el stack fijado, Mockito y reactor-test. | Dependencias y ejecución de suites; el texto del PDF los presenta como ejemplos de frameworks. | PENDIENTE |
| E10 | PDF, p. 6 | Colección Postman, Insomnia o curl que demuestre flujos principales. | Colección ejecutable con autenticación de prueba, variables y aserciones. | Evento, compra, confirmación, consulta, expiración, cortesía, idempotencia y errores. | PENDIENTE |
| E11 | PDF, p. 6 | Sustentación técnica estructurada de la solución y decisiones. | Guion de aproximadamente veinte minutos y recorrido por requisitos, fallos y evidencias. | Ensayo cronometrado y demostración repetible sin modificar datos manualmente para aparentar resultados. | PENDIENTE |

## 6. Criterios de evaluación y diferenciales

| ID | Origen / página | Requisito o criterio | Implementación planeada | Prueba o evidencia planeada | Estado |
|---|---|---|---|---|---|
| V01 | PDF, p. 7 | Cumplimiento y coherencia funcional. | Trazabilidad completa entre requisitos, contratos, implementación y pruebas. | Cierre de todas las filas aplicables con evidencia identificada. | PENDIENTE |
| V02 | PDF, p. 7 | Justificar patrones, concurrencia, consistencia, asincronía y eventos. | ADR sobre límites de servicios, saga, transacciones locales, particionado, outbox e inbox. | Sustentación con fallas concretas y decisiones alternativas razonadas. | PENDIENTE |
| V03 | PDF, p. 7, adicional | Escalabilidad, resiliencia y tolerancia a fallos. | Concurrencia acotada, contadores particionados, DLQ, reconciliación y recuperación. | Carga, backlog, reinicios y fallos; resultados reales con hardware y configuración. | PENDIENTE |
| V04 | PDF, p. 7 | Secretos y credenciales seguros. | Roles mínimos, OIDC, configuración externa y referencias a secretos; sin secretos en Git, imágenes o logs. | Gitleaks, revisión IAM, pruebas de ausencia de filtración y análisis de configuración. | PENDIENTE |
| V05 | PDF, p. 7 | Defensa ante retries maliciosos, abuso e idempotencia. | Autorización por propietario, límites de entrada, frecuencia y concurrencia, idempotencia por actor/operación. | Clave ajena, cuerpo distinto, token inválido, orden ajena, carga abusiva y errores `429`. | PENDIENTE |
| V06 | PDF, pp. 7–8 | Explicación clara, diagramas de arquitectura y flujo/secuencia. | Diagramas de contexto, contenedores, compra, confirmación, estados, GitFlow, CI y AWS. | Correspondencia revisada con contratos, propiedad de datos y código final. | PENDIENTE |
| V07 | PDF, p. 8, diferencial | Terraform; explicar networking, seguridad, escalabilidad y aislamiento. | Módulos compartidos y raíces separadas DEV, QA y PROD; estados, cuentas y recursos aislados. | `fmt`, `validate`, pruebas Terraform, TFLint y Checkov; plan autenticado cuando se disponga de ambiente. | PENDIENTE |
| V08 | PDF, p. 8, alto valor agregado | Buenas prácticas AWS y operación cloud-native. | Fargate privado, DynamoDB, SQS, ingreso autenticado, endpoints privados, roles y observabilidad. | Diagramas y configuración; IAM/red y ejecución real se acreditarán únicamente si se despliega AWS. | PENDIENTE |
| V09 | PDF, p. 8 | Costos, observabilidad y gobernanza. | Retención por ambiente, escalado máximo, alarmas, tags, estimación y procedimiento de desmontaje. | Dashboard, runbooks, controles presupuestarios y costos calculados por región/duración. | PENDIENTE |
| V10 | PDF, p. 8 | Limitaciones, mejoras y decisiones para producción real. | Diferenciar garantías implementadas, metas, validación local, IaC estática y AWS real. | Sección de limitaciones y respuestas sobre TLS interno, capacidad medida, reloj, recovery y operación. | PENDIENTE |

## 7. Ampliaciones expresamente solicitadas por el usuario

| ID | Origen / página | Requisito adicional | Implementación planeada | Prueba o evidencia planeada | Estado |
|---|---|---|---|---|---|
| U01 | Usuario; sin página | Plan y proyecto desde cero, sin reutilizar contexto de trabajos previos. | Utilizar el PDF, las decisiones de alcance del proyecto y fuentes técnicas públicas en un repositorio nuevo. | ADR de contexto y registro de generación; no importar código ni decisiones de proyectos anteriores. | PENDIENTE |
| U02 | Usuario; sin página | Utilizar scaffold Bancolombia. | Generar oficialmente los tres proyectos y adaptadores necesarios. | Versiones fijadas, comandos de generación y `validateStructure` por servicio. | PENDIENTE |
| U03 | Usuario; decisión de alcance | Catálogo, Órdenes e Inventario como microservicios. | Propiedad independiente de datos; Órdenes coordina saga e Inventario conserva invariantes. | Diagramas, contratos y pruebas que impidan escritura cruzada entre propietarios. | PENDIENTE |
| U04 | Usuario; decisión de alcance | Confirmación explícita y entradas numeradas. | Compra de 1–10 entradas distintas; endpoint posterior de confirmación; sin pagos reales. | Colección completa, selección de IDs y exclusividad de asignación. | PENDIENTE |
| U05 | Usuario; decisión de alcance | Local completo y AWS desplegable, sin gasto obligatorio. | Compose evaluable; Terraform preparado sin `apply` automático. | Arranque local; validación IaC; declaración del estado real de cada evidencia. | PENDIENTE |
| U06 | Usuario; corrección de fechas | Hitos del viernes 13 al domingo 15 de noviembre de 2026. | Secuencia de entregas y commits incrementales, conservando timestamps reales. | Historial Git y evidencia efectiva; el cronograma nominal no acredita actividad ocurrida en fechas pasadas. | PENDIENTE |
| U07 | Usuario; sin página | `main` como base; ramas `develop` y `release`; trabajo desde `develop` y merges sucesivos. | `feature/fix/chore → develop → release → main`; PR, checks y preservación del historial. | Rechazo de rutas de PR no autorizadas, force push y promoción sin checks. | PENDIENTE |
| U08 | Usuario; sin página | Variables y configuración por ambiente desde IaC. | `develop → dev`, `release → qa`, `main → prod`; valores tipados, estados separados y recursos por ambiente. | Guardas de cuenta/rama, ausencia de referencias cruzadas y de secretos en `tfvars`/outputs. | PENDIENTE |
| U09 | Usuario; selección de modalidad | Sonar local gratuito. | Community Build persistente, tres proyectos independientes y un perfil Docker de calidad. | Bootstrap reproducible y análisis correcto de bytecode y cobertura por servicio. | PENDIENTE |
| U10 | Usuario; corrección posterior | Validar Sonar en DEV, sin esperar a `main`. | Analizar exclusivamente el SHA de `develop`; gate de los tres proyectos bloquea `develop → release`. | Gate rojo, ausente, inaccesible o de otro SHA impide promoción. No afirmar análisis nativo de PR de Community. | PENDIENTE |
| U11 | Usuario; sin página | Escaneo estático en cada commit. | PMD para Java, Gitleaks y controles IaC aplicables; hook sobre staged y CI sobre cada SHA nuevo, incluidos intermedios y merges. | Push de varios commits y hook omitido; evidencia individual por SHA, herramientas y política; resultado no aplicable registrado explícitamente. | PENDIENTE |
| U12 | Usuario; sin página | Documentación, diagramas y material de sustentación. | Plan maestro, esta matriz, diagramas, contratos, ADR, README, runbooks y guía de demostración. | Revisión de consistencia; documentos finales distinguen diseño propuesto de comportamiento comprobado. | PENDIENTE |
| U13 | Usuario; sin página | Buenas prácticas AWS, IaC modular y Checkov. | Módulos con responsabilidades claras; IAM mínimo, red privada, límites de costos y Checkov en pipeline. | HCL y plan comprobados cuando corresponda; excepciones por recurso con motivo y caducidad, sin exclusiones globales. | PENDIENTE |

## 8. Criterios concretos elegidos para el diseño

Estos valores hacen ejecutable el plan, pero no son umbrales textuales del PDF.

| ID | Origen | Decisión | Evidencia requerida | Estado |
|---|---|---|---|---|
| P01 | Diseño acordado | Dieciséis particiones de inventario por evento y snapshot transaccional de contadores. | Prueba de distribución, integridad del snapshot, contención y costos; no prometer throughput ilimitado. | PENDIENTE |
| P02 | Diseño acordado | Sonar importa cobertura; JaCoCo conserva gates independientes de 90 % de líneas y ramas unitarias. | XML presente y del SHA correcto; verificar denominadores y ausencia de exclusiones artificiales. | PENDIENTE |
| P03 | Diseño acordado | PIT sobre dominio y casos de uso, objetivo de al menos 80 % de mutaciones eliminadas. | Reporte de mutación y revisión de mutantes supervivientes relevantes. | PENDIENTE |
| P04 | Diseño acordado | Metas iniciales de carga: mil usuarios; p95 aceptación <500 ms, disponibilidad <300 ms y confirmación <5 s; cero sobreventa. | k6 con dataset, semilla, hardware, duración, throughput y errores; las metas no equivalen a resultados alcanzados. | PENDIENTE |
| P05 | Diseño acordado | Promover los mismos digests de imagen aprobados en DEV hacia QA y PROD. | Manifiesto con SHA, hash de contenido, digests, contratos, SBOM y análisis; detectar cambios posteriores. | PENDIENTE |
| P06 | Diseño acordado | Congelar merges funcionales durante estabilización de `release`; fixes recorren el flujo completo. | PR y política de promoción; prueba de que no ingresan funcionalidades ajenas al candidato. | PENDIENTE |
| P07 | Diseño acordado | Serializar análisis Sonar por proyecto y esperar el gate del análisis concreto. | `analysisId` ligado al SHA y candidato; fallo ante timeout o evidencia ausente, sin usar “el último análisis” indiscriminadamente. | PENDIENTE |
| P08 | Diseño acordado | La política de análisis no puede desactivarse y autoaprobarse dentro del mismo cambio. | Checks sobre modificaciones a reglas, exclusiones y workflows; comparación contra política controlada y revisión explícita. | PENDIENTE |
| P09 | Diseño acordado | Rollback de artefactos compatible con datos; nunca retroceder estados finales de entradas. | Ensayo de restaurar digest anterior sin rebobinar inventario, y migraciones expand/contract. | PENDIENTE |

## 9. Ambigüedades e interpretaciones que deben quedar visibles

### A01. Reserva inicial, encolado inmediato y respuesta `202`

El PDF combina reservar al iniciar la compra con validación y actualización asíncronas. El usuario eligió **solicitud durable** como significado de `202`.

La implementación planeada guardará orden y outbox antes de responder e intentará una reserva breve. El consumidor recuperará el mismo trabajo si la respuesta todavía es `RESERVING`. Hasta que Inventario confirme la reserva, no se prometerán entradas retenidas. Un registro en outbox tampoco se describirá como un mensaje ya recibido por SQS.

Esta interpretación debe figurar en OpenAPI, README, diagramas y sustentación. La latencia de publicación se medirá y los retrasos serán observables; F02 y F03 no se darán por satisfechos únicamente porque exista una tabla outbox.

### A02. Estados de entrada y de orden

El PDF asigna los mismos cinco estados a entradas y consultas de orden, pero no define aceptación pendiente, rechazo ni expiración. Se conservará `status` con esos cinco valores y se añadirá `workflowStatus`, junto con motivo, versión y asignación activa.

`status=AVAILABLE` en una orden sin asignación activa significará que esa orden no mantiene entradas. No afirmará que sus entradas históricas estén libres para otro cliente. La API de disponibilidad será la autoridad para esa consulta.

### A03. Deadline lógico y liberación materializada

El deadline será de hasta 600 segundos desde la aceptación inicial, inmutable. Ningún retry lo renovará y `PENDING_CONFIRMATION` estará sujeto al mismo plazo.

El barrido cada segundo tendrá un objetivo de liberación física de hasta cinco segundos en operación normal. Esto no constituye una garantía de puntualidad durante una caída. La consulta podrá ser conservadora dentro de esa ventana y deberá señalar indisponibilidad temporal ante atraso superior al objetivo. La reasignación comprobará vencimiento y liberará primero la reserva anterior completa.

Se probarán reloj controlado, límites temporales, confirmación frente a expiración y mensajes antiguos. No se utilizará TTL de DynamoDB para garantizar liberación puntual ni se afirmará precisión distribuida infinita del reloj o del instante de commit.

### A04. Sonar gratuito sobre `develop` y escaneo por commit

SonarQube Community Build tendrá una única rama de análisis por proyecto, alimentada exclusivamente con snapshots del SHA exacto de `develop`. No se afirmará análisis nativo de múltiples ramas ni decoración de PR.

El escaneo de cada commit será independiente: PMD, Gitleaks y los controles IaC aplicables, con evidencia individual. CI incluirá commits intermedios y de merge, creación de rama y escenarios en que se omitió el hook. Un resultado “no aplica” deberá estar justificado y no confundirse con una herramienta que no se ejecutó.

Los tres gates Sonar, con identificadores asociados al candidato, bloquearán la promoción desde `develop` a `release`. Los checks unitarios, de seguridad, concurrencia y recuperación seguirán siendo obligatorios aunque Sonar esté verde.

## 10. Límites de la evidencia y cierre

- ElasticMQ se utiliza como alternativa local compatible con la interfaz SQS; los permisos, políticas y comportamiento reales de AWS requieren comprobación en AWS cuando se despliegue.
- Terraform validado estáticamente no acredita un despliegue exitoso ni capacidad cloud.
- Los diagramas de AWS representarán diseño propuesto mientras no exista despliegue verificado.
- El calendario 13–15 de noviembre de 2026 es un desglose nominal de hitos. El trabajo realizado conservará su fecha efectiva y sus resultados reales.
- No se atribuirán estas decisiones a estándares internos no públicos de Nequi ni se garantizará una calificación o resultado de selección.

Para cerrar una fila se registrará, como mínimo:

```text
ID de requisito:
Commit / candidato:
Tipo de evidencia: unitario | integración local | estático | AWS real
Comando o procedimiento:
Configuración y ambiente:
Resultado observado:
Ubicación del reporte:
Limitación pendiente, si existe:
Fecha de verificación:
```

La matriz estará cerrada únicamente cuando cada requisito aplicable disponga de evidencia suficiente y las interpretaciones anteriores sean consistentes con el contrato, el comportamiento implementado y la explicación de la solución.
