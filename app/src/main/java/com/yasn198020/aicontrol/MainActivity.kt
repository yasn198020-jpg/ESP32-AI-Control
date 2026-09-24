@Composable
private fun LogScreen(modifier: Modifier, log: List<String>, onClear: () -> Unit) {
    val context = LocalContext.current
    var trace by remember { mutableStateOf(DiagnosticTrace.read()) }
    var backgroundTrace by remember { mutableStateOf(BackgroundTrace.read()) }
    var filter by remember { mutableStateOf("ALL") }
    var logMode by remember { mutableStateOf("MAIN") }

    LaunchedEffect(Unit) {
        while (true) {
            trace = DiagnosticTrace.read()
            backgroundTrace = BackgroundTrace.read()
            delay(750)
        }
    }

    val filteredTrace = trace.filter { line ->
        when (filter) {
            "MQTT" -> line.contains(" MQTT]") || line.contains("[MQTT]")
            "SCENARIO" -> line.contains(" SCENARIO]") || line.contains("[SCENARIO]") || line.contains(" CONDITION]") || line.contains(" EDGE]")
            "ACTION" -> line.contains(" ACTION]")
            "VERIFY" -> line.contains(" VERIFY]")
            "ERROR" -> line.contains(" ERROR]")
            else -> true
        }
    }
    val visibleLines = if (logMode == "BACKGROUND") backgroundTrace else filteredTrace
    val traceText = visibleLines.joinToString("\n")

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (logMode == "BACKGROUND") "Отдельный лог фона" else "Диагностика MQTT / сценариев",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (logMode == "BACKGROUND") "Сохранено: ${backgroundTrace.size} событий" else "Сохранено: ${trace.size} событий",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    if (logMode == "BACKGROUND") {
                        BackgroundTrace.clear()
                        backgroundTrace = emptyList()
                    } else {
                        DiagnosticTrace.clear()
                        trace = emptyList()
                    }
                }) {
                    Text("Очистить")
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic Trace", traceText))
                }) {
                    Text("Копировать")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("MAIN" to "Основной", "BACKGROUND" to "Фон").forEach { (id, title) ->
                OutlinedButton(onClick = { logMode = id }) {
                    Text(if (logMode == id) "● $title" else title)
                }
            }
        }

        if (logMode == "MAIN") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("ALL" to "Все", "MQTT" to "MQTT", "SCENARIO" to "Сценарии", "ACTION" to "Действия", "VERIFY" to "Проверка", "ERROR" to "Ошибки").forEach { (id, title) ->
                    OutlinedButton(onClick = { filter = id }) {
                        Text(if (filter == id) "● $title" else title)
                    }
                }
            }
        } else {
            Text(
                "Фиксирует переходы FOREGROUND/BACKGROUND и только события MQTT/сценариев, пришедшие в фоне.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider()

        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visibleLines) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
