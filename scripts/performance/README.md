# Ejecución de rendimiento

La metodología y los límites están en [docs/performance.md](../../docs/performance.md).

```powershell
# No envía tráfico ni crea datos.
python scripts/performance/run.py plan
python scripts/performance/test_run.py
node --test scripts/performance/test_load.cjs
python scripts/performance/run.py validate

# Crea datos y ejecuta carga local. Ejecutar solo fuera de acceptance/expiry/recovery.
python scripts/performance/run.py run --scenario mixed

# Reserva concurrente: 1000 compradores, 100 entradas; 10 compradores por entrada.
$env:PERF_STRESS = '1'
python scripts/performance/run.py run --scenario cohort --vus 1000 --cohort-tickets 100
```

El comando sin argumentos es `plan`. `validate` utiliza `k6 inspect` con red deshabilitada. Solo `run` crea eventos, envía carga y audita resultados.

`--cohort-tickets` conserva el valor predeterminado de 1. La cohorte exige una reserva ganadora única por entrada, rechaza los demás compradores y contrasta entradas paginadas y contadores de Inventory: para el ejemplo, `RESERVED=100`, `SOLD=0` y 900 rechazos. Comprueba reservas concurrentes sin sobreventa; las ventas confirmadas se comprueban por separado en aceptación y el escenario `mixed`.

Imagen oficial fijada: `grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276`. El runner comprueba `k6 v2.4.0` antes de cargar. No usa un tag mutable para ejecutar pruebas.
