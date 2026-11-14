# Guía de sustentación técnica

**Estado: diseño propuesto y preparación de la entrega.** Este documento no certifica que el sistema esté implementado, que las pruebas hayan pasado ni que exista infraestructura desplegada. Las evidencias indicadas deben producirse durante la ejecución del plan.

La propuesta parte del enunciado de la prueba, sin reutilizar contexto de implementaciones anteriores. Su objetivo es una defensa verificable de las decisiones; el resultado de la selección depende de la evaluación y no se puede garantizar.

Stack de referencia: Java 25, Spring Boot 4, WebFlux, scaffold Clean Architecture de Bancolombia, DynamoDB y SQS Standard. Los ambientes son DEV, QA y PROD, definidos con Terraform; SonarQube Community se ejecutará localmente y analizará únicamente `develop`.

## 1. Mensaje central

> La solución separa Catálogo, Órdenes e Inventario. Inventario es la única autoridad sobre las entradas: aplica cambios condicionales y transacciones locales para impedir asignaciones dobles. La comunicación entre servicios admite duplicados y fallos, por lo que se recupera mediante idempotencia, outbox, versiones y reconciliación. Cada commit se analiza; un candidato debe aprobar todos los controles, incluido Sonar en `develop`, antes de promocionarse.

Esta explicación debe poder sostenerse con una transición real, una prueba concurrente y una recuperación reproducible. Un diagrama o un porcentaje de cobertura, por separado, no prueban esas garantías.

## 2. Guion de 20 minutos

| Tiempo | Tema | Qué mostrar cuando esté implementado | Idea que debe quedar clara |
|---|---|---|---|
| 00:00–02:00 | Problema y contrato | Requisitos trazados e invariantes del inventario. | No vender dos veces, no perder solicitudes aceptadas y no confundir reserva con venta. |
| 02:00–04:00 | Arquitectura | Diagrama de servicios, datos propios y mensajería. | Inventario decide; Órdenes coordina; Catálogo publica eventos preparados. |
| 04:00–07:00 | Compra completa | Evento preparado, solicitud, reserva, confirmación y consulta de la orden. | `202` es aceptación durable; `SOLD` requiere confirmación procesada. |
| 07:00–09:00 | Concurrencia | Prueba sobre la última entrada y lectura final de la persistencia. | Las condiciones en DynamoDB protegen la exclusividad, también con varias instancias. |
| 09:00–11:00 | Duplicados y fallos | Repetir una solicitud y un mensaje; detener y reanudar un consumidor. | At-least-once implica duplicados; el efecto de negocio es idempotente. |
| 11:00–13:00 | Expiración y cortesía | Prueba de confirmación contra vencimiento y flujo administrativo. | El plazo no se renueva; una cortesía consume capacidad sin ser venta. |
| 13:00–16:00 | Calidad y promociones | Evidencia por SHA, cobertura, Sonar en `develop` y manifiesto de imágenes. | Un resultado anterior no aprueba código nuevo; se promueven los mismos digests. |
| 16:00–18:00 | AWS e IaC | Módulos, raíces DEV/QA/PROD, identidad y red privada. | El ambiente cambia por configuración; los servicios no llevan credenciales ni ARN hardcodeados. |
| 18:00–20:00 | Límites y evolución | Resultados medidos, decisiones descartadas y trabajo productivo pendiente. | Conocer los límites es parte del diseño y de la operación. |

Reservar las preguntas largas para después del recorrido. Si una demostración falla, identificar el punto, mostrar el error y usar evidencia previamente obtenida con su SHA y fecha; no presentar esa evidencia como una ejecución en vivo.

## 3. Recorrido funcional de demostración

### Escenario A: evento y compra

1. Crear un evento con entradas numeradas y capacidad pequeña, por ejemplo diez entradas.
2. Mostrar `PROVISIONING` y la posterior habilitación cuando Inventario alcance `READY`.
3. Consultar las cantidades iniciales: diez disponibles, cero en los otros estados.
4. Crear una orden por dos entradas con `Idempotency-Key`.
5. Mostrar el identificador de orden y explicar el posible estado inicial `RESERVING`.
6. Consultar hasta observar la reserva autoritativa.
7. Confirmar la orden mediante el endpoint explícito.
8. Consultar `SOLD` y verificar los contadores y la auditoría.

Resultado esperado: ocho entradas disponibles, dos vendidas y una sola asignación por entrada. La evidencia debe provenir de una lectura consistente de Inventario y de pruebas de su persistencia, no exclusivamente de la respuesta HTTP de Órdenes.

### Escenario B: idempotencia y concurrencia

1. Repetir la solicitud original con la misma identidad, clave y contenido.
2. Comprobar que se recupera la misma operación sin otra reserva.
3. Reutilizar la clave con contenido diferente y mostrar el conflicto.
4. En otro evento, lanzar cien compradores simultáneos por una sola entrada.
5. Verificar exactamente una asignación activa y ausencia de cantidades negativas.
6. Entregar nuevamente un mensaje ya procesado y demostrar que no repite el efecto.

Separar en los resultados los rechazos por inventario de los errores técnicos. Una respuesta de conflicto esperada no constituye una caída del servicio.

### Escenario C: vencimiento y recuperación

1. Mostrar una reserva sin confirmar y su deadline persistido.
2. Presentar pruebas deterministas en los instantes anterior, igual y posterior al vencimiento.
3. Demostrar la carrera entre confirmación y expiración: solo un resultado terminal puede ganar.
4. Detener un consumidor después de aceptar trabajo durable.
5. Mostrar la solicitud pendiente, la edad del mensaje o del outbox y la ausencia de una venta ficticia.
6. Reanudar y comprobar recuperación o expiración, según el deadline original.

No reducir silenciosamente los diez minutos del contrato para hacer más corta la presentación. Para una demo de 20 minutos, se puede preparar una reserva al iniciar el recorrido y observar su expiración más adelante; las pruebas temporales automatizadas utilizarán `Clock` inyectable y no esperas reales de diez minutos.

### Escenario D: cortesías

1. Intentar la operación con una identidad sin rol administrativo.
2. Ejecutarla con el rol autorizado, beneficiario y motivo.
3. Verificar `COMPLIMENTARY`, auditoría y capacidad ocupada.
4. Confirmar que el contador de ventas no aumentó y que la entrada no vuelve a `AVAILABLE`.

## 4. Preguntas difíciles y respuestas defendibles

| Pregunta | Respuesta basada en el diseño | Evidencia que debe producirse |
|---|---|---|
| ¿Un `202` garantiza que ya tengo mis entradas? | No. Garantiza que la orden y el trabajo se aceptaron de forma durable. Puede seguir `RESERVING`; la reserva se confirma cuando Inventario aplica su transacción. Esta interpretación se documenta expresamente. | Contrato OpenAPI, respuesta inicial y consulta posterior; prueba con consumidor detenido. |
| ¿Se cumplió el encolado si solo existe outbox? | Outbox es intención durable de publicación. No se afirmará que SQS recibió el mensaje hasta tener su respuesta. El relay y su objetivo de latencia explican la ventana entre ambos hechos. | Traza de commit, publicación y recepción; prueba de caída de SQS. |
| ¿Por qué tres microservicios si aumenta la complejidad? | Es el límite elegido para la prueba. Introduce saga, contratos y reconciliación. Las invariantes de entradas permanecen dentro de Inventario para evitar repartir la decisión de exclusividad entre servicios. | ADR de límites; ausencia de escrituras cruzadas; secuencias de recuperación. |
| ¿Una transacción garantiza toda la compra distribuida? | Solo garantiza el cambio local en su propietario. Órdenes e Inventario no comparten una transacción ACID. La saga tolera estados intermedios y reconstruye el resultado autoritativo. | Prueba que interrumpe la actualización de Órdenes después de reservar en Inventario. |
| ¿Cómo se evita vender dos veces? | Cada asiento se reclama mediante condiciones sobre estado, versión y reserva. Todas las entradas de una orden cambian juntas. Un lock en memoria no sería suficiente entre instancias. | Prueba concurrente real contra DynamoDB Local y verificación de asignaciones. |
| ¿Qué límites tiene una transacción de DynamoDB? | El diseño debe respetar 100 acciones y 4 MB. Se agrupan deltas por contador y se limita cada orden a diez entradas. No se operará dos veces sobre el mismo ítem en una transacción. | Test del constructor de transacciones y cómputo de acciones; payloads máximos. |
| ¿Por qué dieciséis particiones de inventario? | Reducen la contención de un contador global. Las entradas seleccionadas determinan sus particiones. Es un punto inicial medible, no una promesa de throughput ilimitado. | Modelo de claves, benchmark distribuido y escenario de última entrada. |
| ¿Qué costo tiene ese particionado? | La disponibilidad lee dieciséis contadores transaccionalmente y las compras pueden actualizar varios. El diseño intercambia más operaciones por menor contención. | Medición de latencia, cantidad de operaciones y estimación de consumo. |
| ¿Dieciséis lecturas consistentes independientes son un snapshot? | No necesariamente. La consulta utilizará `TransactGetItems` para una vista conjunta de los contadores. | Prueba de disponibilidad durante transiciones concurrentes. |
| ¿Por qué no liberar con TTL? | TTL limpia registros de manera eventual; no es un temporizador puntual. El deadline se comprueba en negocio y la liberación usa un proceso explícito. | Tests de expiración sin depender del borrado TTL. |
| ¿Qué ocurre si el barrido se retrasa? | La reserva ya no puede confirmarse cuando venció. El barrido materializa la devolución y una compra puede recuperar una reserva vencida completa antes de reclamar sus entradas. La disponibilidad puede ser conservadora durante el retraso; si supera el objetivo, no se presentará como actualizada. | Métrica de atraso, prueba con worker detenido y recuperación de reserva completa. |
| ¿Qué reloj decide el vencimiento? | Inventario evalúa un reloj UTC inyectable, con deadline persistido e inmutable. Cada retry vuelve a comprobarlo. La transacción serializa la disputa de estado; el diseño no promete precisión física ilimitada de reloj o red. | Tests de frontera temporal y carrera; política de sincronización de reloj documentada. |
| ¿Puedo confirmar antes del deadline y vender mucho después? | No. La confirmación asíncrona debe superar las verificaciones de plazo al completar. Entrar en `PENDING_CONFIRMATION` no extiende la reserva. | Mensaje de confirmación demorado y expiración antes de `SOLD`. |
| ¿Por qué guardar rechazos de reserva? | Para que un reintento antiguo no reserve después una solicitud que ya recibió un resultado terminal de rechazo. La creación de ese resultado también se condiciona para competir correctamente con un duplicado exitoso. | Carrera entre reserva duplicada, rechazo y nueva disponibilidad. |
| ¿Qué alcance tiene una clave idempotente? | Usuario, operación y clave. Se compara un hash canónico del contenido. La clave de otro cliente no puede recuperar una orden ajena. | Repetición concurrente, contenido diferente y dos usuarios con la misma clave. |
| ¿Outbox produce exactamente una publicación? | No. Puede publicar otra vez si cae después del envío y antes de registrar el resultado. Conserva el trabajo; la deduplicación y las condiciones protegen el efecto de negocio. | Fallo inducido en esa ventana y evento repetido sin doble efecto. |
| ¿Por qué inbox además del estado de la reserva? | Inbox evita reprocesamiento por identidad del mensaje; las condiciones del dominio siguen siendo necesarias para duplicados semánticos, eventos viejos y mensajes con otra identidad. | Tests con mismo `messageId` y con distinto `messageId` para la misma operación. |
| ¿Cuándo se elimina un mensaje de SQS? | Después del resultado durable. Si el proceso cae antes del ACK, la redelivery recupera ese resultado. | Caída posterior al commit y anterior al ACK. |
| ¿La DLQ resuelve el error? | No. Aísla trabajo no procesable y activa operación. El runbook identifica causa, corrige y reproduce con la identidad original cuando sea válido. | Poison message, alarma y replay controlado. |
| ¿Qué evita que un evento viejo revierta una venta? | Órdenes aplica versiones crecientes de Inventario; Inventario protege estados finales y pertenencia de la reserva. | `SOLD` seguido de un snapshot `RESERVED` antiguo. |
| ¿Sonar verde significa 90 % de unitarios? | No por sí solo. JaCoCo impone por servicio 90 % de líneas y ramas unitarias. Sonar importa reportes y añade su Quality Gate. Integración y mutación se reportan separadamente. | XML JaCoCo, importación comprobada y resultados de ambos gates. |
| ¿Por qué Sonar está en DEV y no en MAIN? | La barrera está en `develop`: los tres análisis deben corresponder al candidato antes de promoverlo a `release`. MAIN recibe sus artefactos aprobados. | Tres `analysisId`, SHA, hash de contenido y manifiesto de promoción. |
| ¿Community analiza todas las PR? | No. Los proyectos Sonar reciben exclusivamente snapshots de `develop`, sin parámetros de branch o PR. Las ramas de trabajo se analizan con PMD, Gitleaks y controles de IaC por commit. | Configuración del scanner y evidencia por SHA de ramas de trabajo. |
| ¿Qué significa analizar cada commit de un push? | Se enumeran todos los SHA introducidos, incluidos commits intermedios y merges. Se analiza también la integración. Un push de cinco commits no se reduce a revisar HEAD. | Prueba del workflow con cinco commits, nueva rama e historial ya validado. |
| ¿Puedo saltarme el hook? | El hook es una ayuda temprana y puede omitirse. CI es la autoridad y exige evidencia del snapshot completo de cada commit. | Commit creado omitiendo el hook que es rechazado por CI. |
| ¿Puede un commit desactivar su propio scanner? | No debe poder autoaprobarse. Los cambios de workflows, reglas, exclusiones y política se validan contra una configuración controlada; la evidencia incluye su hash. | Test de eliminación de una regla obligatoria o desactivación de un job. |
| ¿Se usa el último resultado verde de Sonar? | No. Se espera y registra el Quality Gate del análisis concreto, serializando análisis por proyecto. Un resultado anterior no habilita otro candidato. | Candidato nuevo con resultado viejo y prueba de bloqueo. |
| ¿Qué pasa si Sonar está caído? | La promoción queda bloqueada por evidencia faltante. Un timeout no se transforma en aprobación ni en una excepción automática. | Prueba de indisponibilidad y recuperación del job. |
| ¿Cómo se evita reconstruir código diferente para PROD? | Se promueven digests inmutables de las mismas imágenes. Las diferencias de ambiente se inyectan desde IaC. Los SHA de merge pueden cambiar; el contenido aprobado y el manifiesto deben coincidir. | Comparación de digests y hashes entre DEV, QA y PROD. |
| ¿Cómo hablan las tareas privadas sin NAT? | Mediante endpoints privados de DynamoDB, S3, SQS, ECR y Logs. Las imágenes llegan completas. Cada dependencia adicional exige revisar su conectividad. | Diagrama de red, tablas de rutas, endpoints y plan Terraform. |
| ¿Cómo se valida JWT sin descargar JWKS desde las tareas privadas? | API Gateway valida JWT y sobrescribe identidad para la integración privada. Los backends autorizan esa identidad dentro de un ingreso restringido. No se asumirá salida pública de las tareas. | Mappings, authorizer, security groups y prueba de headers falsificados. |
| ¿Hay cifrado extremo a extremo entre servicios? | El perfil de demostración contempla TLS externo e aislamiento privado interno. TLS y autenticación de workloads se presentan como evolución productiva, sin atribuir al perfil garantías que no implementa. | ADR de seguridad y diagrama de límites de confianza. |
| ¿Cómo se hace rollback sin perder ventas? | Se vuelve al digest anterior compatible. No se rebobina la base de inventario ni se revierte `SOLD`. Los cambios de datos deben permitir compatibilidad mediante expand/contract. | Ensayo de rollback con órdenes vendidas antes y después del despliegue. |

## 5. Checklist de preparación

### Repositorio y versión

- [ ] Identificar el SHA exacto y el tag candidato que se van a presentar.
- [ ] Verificar que la documentación corresponda a ese contenido.
- [ ] Mantener árbol de trabajo limpio o explicar cualquier archivo local de preparación.
- [ ] Tener manifiesto de imágenes, hashes y evidencia por servicio.
- [ ] Conservar fechas reales de commits y ejecuciones.
- [ ] Mostrar el flujo `feature/fix/chore → develop → release → main` sin promociones excepcionales ocultas.
- [ ] Comprobar que no se hayan incorporado funcionalidades durante la estabilización de release.

### Arranque y datos

- [ ] Ejecutar el recorrido desde un clon limpio en una prueba previa.
- [ ] Verificar `docker compose up --build --wait` con sus dependencias.
- [ ] Inicializar tablas y colas de forma idempotente.
- [ ] Usar datos e identidades sintéticos; no datos personales reales.
- [ ] Crear eventos específicos para cada escenario y anotar sus identificadores.
- [ ] Separar las entradas de compra normal, contención, expiración y cortesía.
- [ ] No depender de datos residuales de otra ejecución.
- [ ] Mantener tokens fuera de la pantalla, colección exportada y logs.

### Calidad

- [ ] Tener reportes unitarios y JaCoCo por servicio.
- [ ] Confirmar 90 % de líneas y ramas unitarias, sin sumar integración para cubrir el déficit.
- [ ] Verificar que Sonar importó los XML y el bytecode correctos.
- [ ] Tener los tres Quality Gates correspondientes al candidato de `develop`.
- [ ] Registrar `analysisId`, SHA, estado y hash de políticas.
- [ ] Tener evidencia de PMD, secretos e IaC por cada commit introducido, incluidos merges.
- [ ] Registrar expresamente los controles que no aplican; no confundirlos con checks omitidos.
- [ ] Revisar hotspots y excepciones con justificación, sin aceptaciones masivas.
- [ ] Tener pruebas de fallos del pipeline: scanner ausente, reporte faltante, gate rojo y SHA incorrecto.

### Funcionalidad y recuperación

- [ ] Ejecutar la colección completa de solicitudes.
- [ ] Verificar las invariantes contra la persistencia.
- [ ] Confirmar que una reserva múltiple nunca queda parcialmente aplicada.
- [ ] Preparar el escenario de expiración sin modificar silenciosamente el contrato temporal.
- [ ] Verificar duplicados, desorden, reinicio y pérdida de respuesta HTTP.
- [ ] Preparar la detención y reanudación del consumidor con comandos documentados.
- [ ] Separar errores técnicos de rechazos de negocio en las métricas.

### Presentación

- [ ] Abrir únicamente los diagramas, contratos y reportes necesarios.
- [ ] Ensayar el guion con cronómetro.
- [ ] Tener una tabla de limitaciones visible.
- [ ] Distinguir resultados locales, estáticos y AWS reales.
- [ ] No utilizar resultados de rendimiento sin hardware, dataset, duración y SHA.
- [ ] Evitar métricas de éxito o badges cuya evidencia corresponda a otra versión.

## 6. Paquete de evidencia por producir

| Evidencia | Contenido mínimo | Qué no demuestra por sí sola |
|---|---|---|
| Unitarios | Servicio, SHA, casos, resultado y cobertura de líneas/ramas. | Atomicidad real del almacenamiento. |
| Integración local | Versiones de emuladores, configuración, SHA y resultados. | IAM, cuotas y comportamiento operativo completo de AWS. |
| Concurrencia | Entradas disputadas, compradores, sincronización, resultados e invariantes finales. | Capacidad ilimitada ni todos los patrones de acceso. |
| Carga | Hardware, recursos de contenedores, dataset, semilla, duración, throughput, p50/p95/p99 y errores. | Rendimiento equivalente en una cuenta AWS no probada. |
| Análisis por commit | SHA, herramientas y versiones, hash de política y resultados. | Ausencia absoluta de defectos o vulnerabilidades. |
| Sonar | Proyecto, `analysisId`, SHA, métricas, gate y revisión de hotspots. | Reemplazo de pruebas o análisis nativo de PR en Community. |
| IaC | `fmt`, `validate`, pruebas, TFLint, Checkov y plan cuando exista. | Recursos aplicados o conectividad real si no se desplegó. |
| Promoción | Origen/destino, candidato, digests, hash de contenido y gates. | Salud real de un ambiente que no se inició. |
| Recuperación | Falla inducida, punto de interrupción, reinicio y estado autoritativo final. | Recuperación de cualquier falla imaginable. |
| Rollback | Digest previo, compatibilidad y verificación de datos preservados. | Posibilidad de deshacer ventas finales. |

Los reportes deberán indicar fecha, entorno y versión. Si se publica una captura de un dashboard, acompañarla del identificador de análisis o ejecución que permita rastrearla.

## 7. Límites que se deben declarar

1. **AWS preparado no significa AWS probado.** Si no se ejecutó `apply` y una validación real, presentar Terraform como desplegable y analizado estáticamente.
2. **Emulación no equivale a servicio administrado.** DynamoDB Local y ElasticMQ ayudan a probar integración y recuperación, pero no certifican IAM, cuotas, latencias, redrive ni todos los casos de AWS.
3. **Community tiene una sola rama de análisis.** El historial Sonar se alimenta exclusivamente de `develop`. No se promete decoración ni análisis nativo de todas las PR.
4. **Diez minutos son un deadline de negocio.** El worker puede tardar en materializar la liberación. Explicar la comprobación de vencimiento, la recuperación y el objetivo de atraso.
5. **No hay pagos ni ingresos monetarios modelados.** Se demuestra la diferencia entre cantidad vendida, reservada y cortesía; no una integración bancaria.
6. **Tres servicios tienen un costo de consistencia.** Hay estados intermedios y proyecciones; no existe una transacción distribuida entre Órdenes e Inventario.
7. **El particionado no elimina todos los conflictos.** Una misma entrada muy disputada seguirá teniendo contención legítima.
8. **Sonar y cobertura no son pruebas de ausencia de fallos.** Se complementan con concurrencia, fault injection, seguridad y revisión.
9. **Red privada no equivale a TLS interno.** La frontera del perfil de demostración debe coincidir con su diagrama y sus controles.
10. **Los costos no son cero por eliminar NAT.** Fargate, ALB y endpoints pueden generar cargos mientras existan. Mostrar presupuesto y desmontaje cuando se despliegue.

## 8. Evolución productiva defendible

Estas mejoras se presentarán como trabajo futuro y no como capacidades entregadas:

- TLS y autenticación de workloads, con operación de certificados y costos explícitos.
- Validación real en AWS de permisos, conectividad, cuotas, escalado y recuperación.
- Ensayos de restauración y definición de RPO/RTO con el propietario del producto.
- Capacidad y cantidad de particiones ajustadas mediante mediciones de demanda.
- Políticas de retención de auditoría y datos definidas con los responsables del sistema.
- Protección de abuso distribuido y controles de admisión para eventos de demanda extrema.
- Análisis nativo de ramas/PR si se adopta una edición Sonar que lo soporte.
- Separación operativa de API y workers cuando la medición justifique escalarlos de forma independiente.
- Integración de pagos únicamente con un contrato explícito de autorización, confirmación, expiración y compensación.

## 9. Cierre de la presentación

El cierre debe enlazar tres pruebas: exclusividad de entradas, recuperación de trabajo aceptado y trazabilidad del candidato aprobado en DEV hasta el artefacto promovido.

Frase sugerida, ajustada a lo que realmente se haya demostrado:

> Estas son las garantías que verificamos, estas son las fallas que reproducimos y estos son los límites pendientes. El mismo candidato aprobado en DEV es el que se promueve, con configuración e infraestructura separadas por ambiente.

No completar esa frase con resultados todavía pendientes. Si falta una evidencia, nombrarla y ubicarla en el plan de validación.
