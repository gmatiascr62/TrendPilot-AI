# TrendPilot AI

App Android nativa (Kotlin + Jetpack Compose), de uso personal, para swing trading de seguimiento de tendencia en **Binance Spot (solo pares USDT)**, con revisión por IA (Gemini principal; Claude Haiku y GPT de respaldo).

> **No es un bot que promete ganancias.** El trading de criptomonedas puede hacerte perder dinero. Un stop no garantiza el precio de salida ni la pérdida máxima. Backtests y paper trading no predicen resultados futuros.

Estado actual: **modo PAPER por defecto, modo REAL deshabilitado** y protegido por checklist, verificación de la API key, biometría y doble confirmación escrita. El modo real apunta por defecto al **testnet** de Binance.

---

## Qué hace

| Pieza | Descripción |
|---|---|
| Universo | Top 20 por capitalización según **CoinGecko** (fuente externa), filtrado a pares Spot USDT operables en Binance; excluye stablecoins, tokens envueltos/staking, apalancados, ilíquidos (< 20 M USDT/24 h) y colisiones de ticker. Se actualiza a diario; salir del top 20 no obliga a vender. |
| Estrategia | Velas diarias cerradas. Tendencia confirmada (precio > SMA50 > SMA200 con SMA50 ascendente), régimen de BTC, RSI, MACD, volumen relativo, ATR, soportes/resistencias (pivotes), volatilidad, correlación con BTC. Dos gatillos: continuación y recuperación de retroceso. No compra sobreextendido (RSI > 75 o > 3 ATR sobre SMA20) ni con resistencia a < 1 ATR. |
| Motor de riesgo | Determinista y con la última palabra: 1 % de riesgo al stop (incluye comisiones, slippage y margen de gap), máx. 70 % expuesto, ≥ 30 % de reserva USDT, máx. 25 % por activo, máx. 3 posiciones, filtros de Binance (`LOT_SIZE`, `MARKET_LOT_SIZE`, `PRICE_FILTER`, `NOTIONAL`). Si el capital no alcanza para una orden **válida y protegible**, no opera. |
| Protección | Stop en el exchange confirmado inmediatamente después de cada compra (`STOP_LOSS`, o `STOP_LOSS_LIMIT` si el par no admite el primero). Si no se puede confirmar: protocolo de emergencia (bloquea compras y cierra la posición sin protección). |
| Trailing | Chandelier ATR (máximo − 3 ATR), soporte por pivotes y break-even tras 1.5 R. Solo sube, y solo con pasos significativos (≥ 0.5 ATR o 1 %). |
| IA | Revisa candidatos ya generados por las reglas y las posiciones abiertas. Responde JSON con esquema estricto. **No puede originar compras, no ve credenciales y no envía órdenes.** Solo puede endurecer stops, nunca aflojarlos. |
| Modo defensivo | Caída de BTC en 24 h (5 %, **parámetro de prueba, no validado**), BTC bajo SMA200, caída mediana del universo, amplitud de mercado y alerta de la IA. Las reglas duras prevalecen. |
| Guardas | Drawdown máximo 8 % desde el máximo (requiere reactivación manual). Pérdida diaria 2 % (se reanuda el siguiente día UTC). Política de conectividad conservadora (ver abajo). |

### Decisiones confirmadas (2026-10-16)
- Drawdown máximo: **8 %**, con reactivación manual.
- Pérdida diaria: **2 %**, se reanuda al siguiente día UTC.
- IA, en orden: **Gemini** (`gemini-flash-lite-latest`, nivel gratuito de Google AI Studio; si se satura prueba `gemini-flash-latest`) como principal; **Claude Haiku 5.5** (`claude-haiku-5-5`) como primer respaldo; **`gpt-5.6-terra`** como último respaldo. El orden y los modelos se cambian en Ajustes (Opus 5.5 sigue disponible).
- Conectividad, política "conservadora":
  - Sin conexión: no hay compras ni ajustes de stop. Los stops en Binance siguen activos.
  - Al reconectar: reconciliación REST completa.
  - Si estuvo más de 30 min offline o hay discrepancias, las compras quedan pausadas hasta que confirmes.

---

## Arquitectura

```
core/   Kotlin puro (sin Android), con tests unitarios
  filters/     Filtros de Binance con BigDecimal exacto
  indicators/  SMA, EMA, RSI (Wilder), MACD, ATR, pivotes, volatilidad, correlación
  strategy/    TrendStrategy, régimen BTC, ExitEvaluator, TrailingStopPolicy
  risk/        RiskEngine, EquityGuard (drawdown/pérdida diaria), DefensiveMode, ConnectivityMonitor
  execution/   ExecutionCoordinator: único emisor de órdenes, mutex por cuenta, intenciones persistidas
  paper/       PaperBroker: exchange simulado con la misma interfaz y fallos inyectables
  backtest/    BacktestEngine sin look-ahead + walk-forward
  ai/          Contrato JSON, validador determinista, router Gemini→Claude→GPT con presupuesto
  engine/      TradingEngine: ciclos de 5 min (supervisión) y 4 h (análisis)
infra/  JVM: cliente REST/WebSocket de Binance, CoinGecko, proveedores Gemini (REST), Claude (SDK oficial) y OpenAI
app/    Android: Room, Keystore, servicio foreground, WorkManager, 8 pantallas Compose
```

### Ejecución segura de órdenes
- **Antes de cada orden** el motor relee saldos, órdenes abiertas, precio reciente, filtros, protecciones, exposición, límites, modo defensivo, conectividad, IA, presupuesto y duplicados.
- **Intención transaccional.** La intención se guarda en Room **antes** de enviarse, con un `clientOrderId` derivado de su id (`tp` + modo + tipo + id).
- **Estado desconocido.** Ante un timeout (`-1007`, 5xx o caída de red después del envío), la orden se **consulta** por `origClientOrderId` y nunca se reenvía a ciegas. Solo se declara "no colocada" cuando pasó el `recvWindow`, porque desde ese momento Binance rechaza la petición tardía.
- **Reemplazo de stop.** Se usa `POST /api/v3/order/cancelReplace` con `STOP_ON_FAILURE` cuando el par lo permite. **No es atómico:** si la cancelación sale bien y la orden nueva falla (`-2021`), la protección se repone de inmediato con el stop nuevo o, si no, con el anterior; si eso también falla, se activa el protocolo de emergencia. Si el par no admite cancelReplace, se cancela y se coloca de nuevo, con el mismo manejo.
- **Ventas parciales.** Primero se reduce la protección con `order/amend/keepPriority`, conservando el mismo `clientOrderId`. Se rechazan si el remanente o la venta violarían mínimos, porque dejarían saldo sin seguimiento.
- **Reconciliación.** Corre al arrancar, al reconectar y cada ciclo. Resuelve intenciones pendientes, reconstruye posiciones de compras huérfanas, detecta stops ejecutados mientras la app no supervisaba, repone stops faltantes y marca órdenes externas y saldos no gestionados.

### Lo que Android NO garantiza
- **No hay 24/7 garantizado.** El servicio foreground (`specialUse`, válido porque la app es de uso personal y no se publica en Play) puede ser demorado o matado por Doze o por el gestor de batería de HyperOS/MIUI. Un `WatchdogWorker` cada 15 min (el mínimo de WorkManager) ejecuta una verificación de protecciones si el servicio murió, y te avisa.
- **La seguridad no depende del teléfono.** Los stops viven en Binance. Si el teléfono se apaga, el stop sigue activo; lo único que se detiene es el trailing.
- La supervisión cada ~5 min y el WebSocket funcionan cuando el sistema y la red lo permiten.

---

## Instalación del APK

1. En GitHub abrí **Actions → Android CI → la última ejecución verde → Artifacts → `TrendPilotAI-debug-apk`**.
2. Descomprimí el zip e instalá el `.apk` en el teléfono. Hay que permitir "instalar apps de origen desconocido".
3. Al abrir la app, permití las notificaciones.

### Configuración del teléfono (POCO F7 / HyperOS)
- Ajustes → Apps → TrendPilot AI → **Ahorro de batería: Sin restricciones**.
- Activá **Inicio automático**.
- En recientes, bloqueá la app con el candado para que no se cierre al limpiar.
- Tené configurado un bloqueo de pantalla (PIN o biometría): sin él la app no permite editar credenciales ni hacer acciones destructivas.

---

## API keys de mínimo privilegio

Se cargan en **Configuración → Credenciales**, protegido con biometría o PIN. Se cifran con **AES-256-GCM** usando una clave no exportable del **Android Keystore**. Están excluidas de backups (`allowBackup=false` y reglas de extracción vacías). Nunca se registran en logs, nunca se envían a la IA y nunca aparecen en informes. Las pantallas usan `FLAG_SECURE`, así que no se pueden capturar. **Nunca** las pongas en el código, en GitHub ni en GitHub Actions.

> Limitación: la clave del Keystore no exige autenticación por uso, porque el motor en segundo plano necesita firmar órdenes con la pantalla apagada. En un teléfono rooteado o comprometido, un atacante podría leerlas de la memoria en ejecución.

### Binance
1. **Testnet primero.** Entrá a https://testnet.binance.vision, generá una key HMAC y usá **Seguridad → REAL testnet**. Esto no mueve dinero real.
2. **Producción**, solo cuando termines el checklist. En Binance → Gestión de API → crear una key **HMAC**:
   - ✅ Habilitar lectura.
   - ✅ Habilitar trading Spot y Margin. La app solo usa Spot y nunca envía órdenes de margen.
   - ❌ **Retiros: deshabilitado.** La app verifica esto con `GET /sapi/v1/account/apiRestrictions` y no habilita el modo real si la key permite retiros.
   - ❌ Futuros, opciones, transferencias universales: deshabilitados.
3. **IP cambiante.** Binance recomienda restringir la key por IP, pero desde un teléfono con datos móviles la IP cambia. Sin restricción de IP, la key queda más expuesta si se filtra, y Binance puede limitar o expirar los permisos de trading de las keys sin restricción. Mitigaciones: sin retiros, una cuenta Spot dedicada solo al bot y rotación periódica de la key. No hay forma de ocultar esta limitación.

### Gemini (Google), principal
1. Entrá a https://aistudio.google.com con tu cuenta de Google y creá una API key ("Get API key").
2. Pegala en **Configuración → Credenciales → Google Gemini API key**.
3. Modelo por defecto `gemini-flash-lite-latest`: en el nivel gratuito tiene una cuota diaria mucho mayor que Flash (que ronda los 20 pedidos por día) y se satura menos. Ante errores temporales (429, 503) la app reintenta y después prueba `gemini-flash-latest`. Según fuentes de terceros, en octubre de 2026 el nivel gratuito solo incluye modelos Flash y Flash-Lite. No lo pude confirmar en la página oficial: revisá https://ai.google.dev/gemini-api/docs/pricing.
4. Con 6 análisis por día, el uso queda muy por debajo de los límites diarios del nivel gratuito. Las tarifas vienen en 0. Si activás facturación en el proyecto de Google, cargá las tarifas pagas en Ajustes para que el presupuesto se controle.
5. **Privacidad:** en el nivel gratuito Google puede usar las consultas para mejorar sus productos. La app envía indicadores de mercado y porcentajes de la cartera; nunca envía claves, IDs de cuenta ni órdenes.
6. Si Gemini falla (límite alcanzado, caída o bloqueo), la app usa Claude Haiku, y después GPT.

### Claude (Anthropic), primer respaldo
El plan **Claude Pro no incluye acceso a la API**. La API se factura aparte:
1. Entrá a https://console.anthropic.com, agregá un método de pago o créditos en **Billing** y, si querés, un límite de gasto mensual.
2. Creá una API key en **API Keys** y pegala en la app.
3. Modelo por defecto `claude-haiku-5-5`, a US$ 0,10 / 0,50 por millón de tokens de entrada / salida (prompts de hasta 100K tokens), con presupuesto de US$ 3 por mes. Con un análisis cada 4 h el costo estimado es de **US$ 0,50–1 por mes**. Si querés un análisis más profundo podés cambiar a `claude-opus-5-5` en Ajustes (US$ 4 / 20, unos US$ 20–30 por mes): actualizá también las tarifas y el presupuesto. La app aplica el presupuesto mensual por proveedor y registra tokens y costo.
4. Con Opus 5.x o Sonnet 5.5 la app activa el fallback de rechazo del lado del servidor (beta `server-side-fallback-2026-07-01`; se desactiva en `claudeServerFallback`). Haiku no lo admite y no se envía.

### OpenAI (último respaldo)
Creá una key en https://platform.openai.com con un límite de uso. Las tarifas de `gpt-5.6-terra` vienen cargadas como **valores de ejemplo**: verificalas en la página oficial de precios y corregilas en Ajustes.

### CoinGecko (opcional)
Una "demo key" gratuita sube el límite de consultas. No es una credencial de trading.

---

## Uso recomendado
1. **Backtesting.** Corré varias ventanas walk-forward y leé las advertencias de sesgo:
   - sesgo de supervivencia del universo actual;
   - las decisiones de la IA no se pueden reconstruir históricamente;
   - los filtros históricos pudieron ser distintos.
2. **Paper trading, como mínimo 30 días**, con precios reales y órdenes simuladas. Cumplir 30 días **no** habilita el modo real automáticamente.
3. Auditá resultados, incidentes y fiabilidad en Historial y Estadísticas.
4. **REAL testnet.** Verificá que las órdenes reales, los stops, la reconciliación y la recuperación se comporten como en paper.
5. **REAL producción**, solo después de:
   - completar el checklist;
   - verificar que la key no permite retiros;
   - pasar la biometría;
   - escribir `PRODUCCION` y `OPERAR REAL`.

   Empezá con un tamaño pequeño.

Acciones manuales:
- **Detener análisis** y **pausar compras** no tocan las protecciones.
- **Vender** cancela solo el stop de esa posición, justo antes de venderla. Si la venta falla, la protección se repone.
- **Liquidar todo** exige escribir `LIQUIDAR`.

---

## Desarrollo

```bash
./gradlew :core:test :infra:test     # tests JVM (no requieren SDK de Android)
./gradlew :app:assembleDebug         # requiere Android SDK (CI lo instala)
```

Los tests cubren:
- límites de riesgo y sizing;
- filtros de Binance;
- indicadores sin look-ahead;
- duplicados ante timeout (antes y después de ejecutar);
- recuperación tras reinicio;
- fill parcial;
- stop rechazado → emergencia;
- reemplazo de stop fallido (`-2021`);
- stop ejecutado offline;
- stop desaparecido;
- ventas parciales;
- desconexión y reanudación manual;
- IA caída o sin presupuesto, y fallback entre proveedores;
- conector de Gemini (esquema JSON, key en cabecera, descarte de partes de razonamiento, bloqueo por seguridad);
- validación del JSON de la IA;
- drawdown y pérdida diaria;
- backtest sin look-ahead;
- selección de universo;
- firma HMAC contra el ejemplo oficial;
- mapeo de errores de Binance (`-2013`, `-1007`, `429`).

Ningún test envía órdenes reales: todo usa el `PaperBroker` o un `MockWebServer`.

Documentación consultada: [binance-spot-api-docs](https://github.com/binance/binance-spot-api-docs) (REST, filtros, enums, errores, WebSocket; changelog al 2026-09-18), SDK oficial `anthropic-java` 2.34.0, la API REST `generateContent` de Gemini (`responseMimeType` + `responseJsonSchema`, según el SDK oficial `python-genai`) y la API de Chat Completions de OpenAI con `response_format: json_schema` estricto.
