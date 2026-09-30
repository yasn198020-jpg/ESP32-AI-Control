package com.yasn198020.aicontrol

sealed interface IoTExpr {
    data class NumberLiteral(val value: Double) : IoTExpr
    data class StringLiteral(val value: String) : IoTExpr
    data class Variable(val name: String) : IoTExpr
    data class Unary(val operator: String, val expression: IoTExpr) : IoTExpr
    data class Binary(val operator: String, val left: IoTExpr, val right: IoTExpr) : IoTExpr
    data class Call(val name: String, val args: List<IoTExpr>) : IoTExpr
}

sealed interface IoTStatement {
    data class If(val condition: IoTExpr, val thenBranch: IoTStatement, val elseBranch: IoTStatement? = null) : IoTStatement
    data class Assignment(val target: String, val operator: String, val expression: IoTExpr) : IoTStatement
    data class ExpressionStatement(val expression: IoTExpr) : IoTStatement
    data class Block(val statements: List<IoTStatement>) : IoTStatement
}

data class IoTScenarioParseResult(
    val statements: List<IoTStatement>,
    val errors: List<String>,
    val referencedIdentifiers: Set<String>
)

data class DeviceScenarioCondition(
    val expression: IoTExpr,
    val rendered: String,
    val identifiers: Set<String>
)

data class DeviceScenarioAction(
    val targetId: String,
    val operator: String,
    val expression: IoTExpr,
    val rendered: String
)

data class DeviceScenarioRule(
    val index: Int,
    val condition: DeviceScenarioCondition,
    val actions: List<DeviceScenarioAction>
)

data class DeviceScenarioModel(
    val rules: List<DeviceScenarioRule>,
    val identifiers: Set<String>,
    val parserErrors: List<String>,
    val statements: List<IoTStatement> = emptyList()
) {
    val valid: Boolean get() = parserErrors.isEmpty() && rules.isNotEmpty()
}

private enum class IoTTokenType {
    IDENTIFIER, NUMBER, STRING, IF, THEN, ELSE,
    LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMICOLON,
    ASSIGN, SILENT_ASSIGN, EQ, NE, LT, LE, GT, GE,
    PLUS, MINUS, MUL, DIV, BAND, BOR, BANG, EOF
}

private data class IoTToken(val type: IoTTokenType, val text: String, val position: Int)

private object IoTScenarioLexer {
    fun tokenize(source: String): Pair<List<IoTToken>, List<String>> {
        val tokens = ArrayList<IoTToken>()
        val errors = ArrayList<String>()
        var i = 0
        fun add(type: IoTTokenType, text: String, start: Int) { tokens += IoTToken(type, text, start) }

        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> while (i < source.length && source[i] != '\n') i++
                c == '"' || c == '\'' -> {
                    val quote = c
                    val start = i++
                    val out = StringBuilder()
                    var closed = false
                    while (i < source.length) {
                        val ch = source[i++]
                        if (ch == '\\' && i < source.length) {
                            val next = source[i++]
                            out.append(
                                when (next) {
                                    'n' -> '\n'
                                    'r' -> '\r'
                                    't' -> '\t'
                                    '\\' -> '\\'
                                    '"', '\'' -> next
                                    else -> next
                                }
                            )
                        } else if (ch == quote) {
                            closed = true
                            break
                        } else out.append(ch)
                    }
                    if (!closed) errors += "Незакрытая строка в позиции " + start
                    else add(IoTTokenType.STRING, out.toString(), start)
                }
                c.isDigit() || (c == '.' && i + 1 < source.length && source[i + 1].isDigit()) -> {
                    val start = i
                    var dot = false
                    while (i < source.length) {
                        val ch = source[i]
                        when {
                            ch.isDigit() -> i++
                            ch == '.' && !dot -> { dot = true; i++ }
                            else -> break
                        }
                    }
                    add(IoTTokenType.NUMBER, source.substring(start, i), start)
                }
                c.isLetter() || c == '_' -> {
                    val start = i++
                    while (i < source.length) {
                        val ch = source[i]
                        if (ch.isLetterOrDigit() || ch == '_' || ch == '.') i++ else break
                    }
                    val word = source.substring(start, i)
                    add(
                        when (word.lowercase()) {
                            "if" -> IoTTokenType.IF
                            "then" -> IoTTokenType.THEN
                            "else" -> IoTTokenType.ELSE
                            else -> IoTTokenType.IDENTIFIER
                        },
                        word,
                        start
                    )
                }
                c == ':' && i + 1 < source.length && source[i + 1] == '=' -> { add(IoTTokenType.SILENT_ASSIGN, ":=", i); i += 2 }
                c == '=' && i + 1 < source.length && source[i + 1] == '=' -> { add(IoTTokenType.EQ, "==", i); i += 2 }
                c == '!' && i + 1 < source.length && source[i + 1] == '=' -> { add(IoTTokenType.NE, "!=", i); i += 2 }
                c == '<' && i + 1 < source.length && source[i + 1] == '=' -> { add(IoTTokenType.LE, "<=", i); i += 2 }
                c == '>' && i + 1 < source.length && source[i + 1] == '=' -> { add(IoTTokenType.GE, ">=", i); i += 2 }
                c == '=' -> { add(IoTTokenType.ASSIGN, "=", i); i++ }
                c == '<' -> { add(IoTTokenType.LT, "<", i); i++ }
                c == '>' -> { add(IoTTokenType.GT, ">", i); i++ }
                c == '+' -> { add(IoTTokenType.PLUS, "+", i); i++ }
                c == '-' -> { add(IoTTokenType.MINUS, "-", i); i++ }
                c == '*' -> { add(IoTTokenType.MUL, "*", i); i++ }
                c == '/' -> { add(IoTTokenType.DIV, "/", i); i++ }
                c == '&' -> { add(IoTTokenType.BAND, "&", i); i++ }
                c == '|' -> { add(IoTTokenType.BOR, "|", i++); }
                c == '!' -> { add(IoTTokenType.BANG, "!", i); i++ }
                c == '(' -> { add(IoTTokenType.LPAREN, "(", i); i++ }
                c == ')' -> { add(IoTTokenType.RPAREN, ")", i); i++ }
                c == '{' -> { add(IoTTokenType.LBRACE, "{", i); i++ }
                c == '}' -> { add(IoTTokenType.RBRACE, "}", i); i++ }
                c == ',' -> { add(IoTTokenType.COMMA, ",", i); i++ }
                c == ';' -> { add(IoTTokenType.SEMICOLON, ";", i); i++ }
                else -> { errors += "Неизвестный символ '" + c + "' в позиции " + i; i++ }
            }
        }
        add(IoTTokenType.EOF, "", source.length)
        return tokens to errors
    }
}

object IoTScenarioParser {
    fun parse(source: String): IoTScenarioParseResult {
        val normalized = source.trim().let {
            val marker = Regex("(?m)^\\s*scenario=>")
            if (marker.containsMatchIn(it)) it.substring(marker.find(it)!!.range.last + 1) else it
        }
        val lexed = IoTScenarioLexer.tokenize(normalized)
        return Parser(lexed.first, lexed.second.toMutableList()).run()
    }

    private class Parser(private val tokens: List<IoTToken>, private val errors: MutableList<String>) {
        private var index = 0
        fun run(): IoTScenarioParseResult {
            val statements = mutableListOf<IoTStatement>()
            while (!check(IoTTokenType.EOF)) {
                if (match(IoTTokenType.SEMICOLON)) continue
                val before = index
                parseStatement()?.let { statements += it }
                if (index == before) index++
            }
            return IoTScenarioParseResult(
                statements,
                errors.toList(),
                statements.flatMapTo(mutableSetOf()) { collectIdentifiers(it) }
            )
        }

        private fun parseStatement(): IoTStatement? = when {
            match(IoTTokenType.IF) -> parseIf(previous())
            check(IoTTokenType.LBRACE) -> parseBlock()
            else -> parseAssignmentOrExpression()
        }

        private fun parseIf(start: IoTToken): IoTStatement? {
            val condition = parseExpression()
            if (!match(IoTTokenType.THEN)) {
                errorAt(current(), "Ожидалось THEN после условия IF")
                synchronizeStatement()
                return null
            }
            val thenBranch = parseStatement()
            if (thenBranch == null) {
                errorAt(start, "Не удалось разобрать ветвь THEN")
                return null
            }
            val elseBranch = if (match(IoTTokenType.ELSE)) parseStatement() else null
            return IoTStatement.If(condition, thenBranch, elseBranch)
        }

        private fun parseBlock(): IoTStatement.Block {
            consume(IoTTokenType.LBRACE, "Ожидалась '{'")
            val statements = mutableListOf<IoTStatement>()
            while (!check(IoTTokenType.RBRACE) && !check(IoTTokenType.EOF)) {
                if (match(IoTTokenType.SEMICOLON)) continue
                val before = index
                parseStatement()?.let { statements += it }
                if (index == before) index++
            }
            consume(IoTTokenType.RBRACE, "Ожидалась '}'")
            match(IoTTokenType.SEMICOLON)
            return IoTStatement.Block(statements)
        }

        private fun parseAssignmentOrExpression(): IoTStatement? {
            val left = parseExpression()
            val op = when {
                match(IoTTokenType.ASSIGN) -> "="
                match(IoTTokenType.SILENT_ASSIGN) -> ":="
                else -> null
            }
            if (op != null) {
                val target = (left as? IoTExpr.Variable)?.name
                if (target == null) {
                    errorAt(previous(), "Левая часть присваивания должна быть идентификатором")
                    return null
                }
                val rhs = parseExpression()
                match(IoTTokenType.SEMICOLON)
                return IoTStatement.Assignment(target, op, rhs)
            }
            match(IoTTokenType.SEMICOLON)
            return IoTStatement.ExpressionStatement(left)
        }

        private fun parseExpression(): IoTExpr = parseBinary(0)

        private fun parseBinary(minPrecedence: Int): IoTExpr {
            var left = parseUnary()
            while (true) {
                val token = current()
                val p = precedence(token.type)
                if (p < minPrecedence) break
                advance()
                left = IoTExpr.Binary(token.text, left, parseBinary(p + 1))
            }
            return left
        }

        private fun parseUnary(): IoTExpr = when {
            match(IoTTokenType.MINUS) -> IoTExpr.Unary("-", parseUnary())
            match(IoTTokenType.PLUS) -> IoTExpr.Unary("+", parseUnary())
            match(IoTTokenType.BANG) -> IoTExpr.Unary("!", parseUnary())
            else -> parsePrimary()
        }

        private fun parsePrimary(): IoTExpr {
            val token = current()
            return when {
                match(IoTTokenType.NUMBER) -> IoTExpr.NumberLiteral(token.text.toDoubleOrNull() ?: 0.0)
                match(IoTTokenType.STRING) -> IoTExpr.StringLiteral(token.text)
                match(IoTTokenType.IDENTIFIER) -> {
                    if (match(IoTTokenType.LPAREN)) {
                        val args = mutableListOf<IoTExpr>()
                        if (!check(IoTTokenType.RPAREN)) {
                            do { args += parseExpression() } while (match(IoTTokenType.COMMA))
                        }
                        consume(IoTTokenType.RPAREN, "Ожидалась ')' после вызова")
                        IoTExpr.Call(token.text, args)
                    } else IoTExpr.Variable(token.text)
                }
                match(IoTTokenType.LPAREN) -> {
                    val e = parseExpression()
                    consume(IoTTokenType.RPAREN, "Ожидалась ')'")
                    e
                }
                else -> {
                    errorAt(token, "Ожидалось выражение")
                    advance()
                    IoTExpr.NumberLiteral(0.0)
                }
            }
        }

        private fun precedence(type: IoTTokenType): Int = when (type) {
            IoTTokenType.BOR -> 1
            IoTTokenType.BAND -> 2
            IoTTokenType.EQ, IoTTokenType.NE -> 3
            IoTTokenType.LT, IoTTokenType.LE, IoTTokenType.GT, IoTTokenType.GE -> 4
            IoTTokenType.PLUS, IoTTokenType.MINUS -> 5
            IoTTokenType.MUL, IoTTokenType.DIV -> 6
            else -> -1
        }

        private fun synchronizeStatement() {
            while (!check(IoTTokenType.EOF)) {
                if (match(IoTTokenType.SEMICOLON)) return
                if (check(IoTTokenType.IF) || check(IoTTokenType.RBRACE) || check(IoTTokenType.ELSE)) return
                advance()
            }
        }

        private fun consume(type: IoTTokenType, message: String): IoTToken {
            if (check(type)) return advance()
            errorAt(current(), message)
            return current()
        }
        private fun check(type: IoTTokenType): Boolean = current().type == type
        private fun match(type: IoTTokenType): Boolean {
            if (!check(type)) return false
            advance()
            return true
        }
        private fun advance(): IoTToken = tokens[index].also { if (index < tokens.lastIndex) index++ }
        private fun previous(): IoTToken = tokens[(index - 1).coerceAtLeast(0)]
        private fun current(): IoTToken = tokens[index]
        private fun errorAt(token: IoTToken, message: String) { errors += message + " (позиция " + token.position + ")" }

        private fun collectIdentifiers(statement: IoTStatement): Set<String> {
            val result = mutableSetOf<String>()
            fun expr(e: IoTExpr) {
                when (e) {
                    is IoTExpr.Variable -> result += e.name
                    is IoTExpr.Unary -> expr(e.expression)
                    is IoTExpr.Binary -> { expr(e.left); expr(e.right) }
                    is IoTExpr.Call -> e.args.forEach(::expr)
                    is IoTExpr.NumberLiteral, is IoTExpr.StringLiteral -> Unit
                }
            }
            fun stmt(s: IoTStatement) {
                when (s) {
                    is IoTStatement.If -> { expr(s.condition); stmt(s.thenBranch); s.elseBranch?.let(::stmt) }
                    is IoTStatement.Assignment -> { result += s.target; expr(s.expression) }
                    is IoTStatement.ExpressionStatement -> expr(s.expression)
                    is IoTStatement.Block -> s.statements.forEach(::stmt)
                }
            }
            stmt(statement)
            return result
        }
    }
}

object IoTScenarioSemanticAnalyzer {
    fun analyze(parsed: IoTScenarioParseResult): DeviceScenarioModel {
        val rules = mutableListOf<DeviceScenarioRule>()
        var nextIndex = 0

        fun combine(left: IoTExpr?, right: IoTExpr): IoTExpr =
            if (left == null) right else IoTExpr.Binary("&", left, right)

        fun negate(expr: IoTExpr): IoTExpr = IoTExpr.Unary("!", expr)

        fun emit(condition: IoTExpr, assignments: List<IoTStatement.Assignment>) {
            if (assignments.isEmpty()) return
            val conditionObj = DeviceScenarioCondition(condition, condition.render(), expressionIdentifiers(condition))
            val actions = assignments.map { a ->
                DeviceScenarioAction(
                    a.target,
                    a.operator,
                    a.expression,
                    a.target + " " + a.operator + " " + a.expression.render()
                )
            }
            rules += DeviceScenarioRule(nextIndex++, conditionObj, actions)
        }

        fun walk(statement: IoTStatement, parent: IoTExpr?) {
            when (statement) {
                is IoTStatement.Assignment -> emit(parent ?: IoTExpr.NumberLiteral(1.0), listOf(statement))
                is IoTStatement.ExpressionStatement -> Unit
                is IoTStatement.Block -> {
                    val assignments = statement.statements.filterIsInstance<IoTStatement.Assignment>()
                    emit(parent ?: IoTExpr.NumberLiteral(1.0), assignments)
                    statement.statements.filterNot { it is IoTStatement.Assignment }.forEach { nested -> walk(nested, parent) }
                }
                is IoTStatement.If -> {
                    walk(statement.thenBranch, combine(parent, statement.condition))
                    statement.elseBranch?.let { walk(it, combine(parent, negate(statement.condition))) }
                }
            }
        }

        parsed.statements.forEach { walk(it, null) }
        return DeviceScenarioModel(
            rules = rules,
            identifiers = parsed.referencedIdentifiers,
            parserErrors = parsed.errors,
            statements = parsed.statements
        )
    }
}

fun expressionIdentifiers(expression: IoTExpr): Set<String> {
    val result = mutableSetOf<String>()
    fun visit(e: IoTExpr) {
        when (e) {
            is IoTExpr.Variable -> result += e.name
            is IoTExpr.Unary -> visit(e.expression)
            is IoTExpr.Binary -> { visit(e.left); visit(e.right) }
            is IoTExpr.Call -> e.args.forEach(::visit)
            is IoTExpr.NumberLiteral, is IoTExpr.StringLiteral -> Unit
        }
    }
    visit(expression)
    return result
}

fun IoTExpr.render(): String = when (this) {
    is IoTExpr.NumberLiteral -> value.toString().removeSuffix(".0")
    is IoTExpr.StringLiteral -> "\"" + value.replace("\"", "\\\"") + "\""
    is IoTExpr.Variable -> name
    is IoTExpr.Unary -> operator + expression.render()
    is IoTExpr.Binary -> "(" + left.render() + " " + operator + " " + right.render() + ")"
    is IoTExpr.Call -> name + "(" + args.joinToString(", ") { it.render() } + ")"
}

fun IoTExpr.render(labels: Map<String, String>): String = when (this) {
    is IoTExpr.NumberLiteral -> value.toString().removeSuffix(".0")
    is IoTExpr.StringLiteral -> "\"" + value.replace("\"", "\\\"") + "\""
    is IoTExpr.Variable -> {
        val label = labels[name]?.trim()
        if (label.isNullOrBlank() || label == name) name else "$label [$name]"
    }
    is IoTExpr.Unary -> operator + expression.render(labels)
    is IoTExpr.Binary -> "(" + left.render(labels) + " " + operator + " " + right.render(labels) + ")"
    is IoTExpr.Call -> name + "(" + args.joinToString(", ") { it.render(labels) } + ")"
}

sealed interface IoTValue {
    data class Number(val value: Double) : IoTValue
    data class Text(val value: String) : IoTValue
    data class BooleanValue(val value: Boolean) : IoTValue
    data object Unknown : IoTValue
}

class IoTScenarioEvaluationContext(
    val variables: Map<String, String>,
    val functions: Map<String, (List<IoTValue>) -> IoTValue> = emptyMap()
)

object IoTScenarioEvaluator {
    fun evaluate(expression: IoTExpr, context: IoTScenarioEvaluationContext): IoTValue = when (expression) {
        is IoTExpr.NumberLiteral -> IoTValue.Number(expression.value)
        is IoTExpr.StringLiteral -> IoTValue.Text(expression.value)
        is IoTExpr.Variable -> variable(expression.name, context)
        is IoTExpr.Unary -> {
            val value = evaluate(expression.expression, context)
            when (expression.operator) {
                "-" -> value.asNumber()?.let { IoTValue.Number(-it) } ?: IoTValue.Unknown
                "+" -> value.asNumber()?.let { IoTValue.Number(it) } ?: IoTValue.Unknown
                "!" -> when (value) {
                    IoTValue.Unknown -> IoTValue.Unknown
                    else -> IoTValue.BooleanValue(!value.asBoolean())
                }
                else -> IoTValue.Unknown
            }
        }
        is IoTExpr.Binary -> evaluateBinary(expression, context)
        is IoTExpr.Call -> context.functions[expression.name.lowercase()]?.invoke(expression.args.map { evaluate(it, context) })
            ?: builtin(expression.name)
    }

    private fun variable(name: String, context: IoTScenarioEvaluationContext): IoTValue {
        val raw = context.variables[name] ?: return when (name.lowercase()) {
            "true" -> IoTValue.BooleanValue(true)
            "false" -> IoTValue.BooleanValue(false)
            else -> IoTValue.Unknown
        }
        return raw.replace(',', '.').toDoubleOrNull()?.let(IoTValue::Number) ?: IoTValue.Text(raw)
    }

    private fun evaluateBinary(e: IoTExpr.Binary, context: IoTScenarioEvaluationContext): IoTValue {
        val left = evaluate(e.left, context)
        val right = evaluate(e.right, context)
        return when (e.operator) {
            "+", "-", "*", "/" -> {
                val a = left.asNumber() ?: return IoTValue.Unknown
                val b = right.asNumber() ?: return IoTValue.Unknown
                if (e.operator == "/" && kotlin.math.abs(b) < 0.0000001) IoTValue.Unknown
                else IoTValue.Number(
                    when (e.operator) {
                        "+" -> a + b
                        "-" -> a - b
                        "*" -> a * b
                        else -> a / b
                    }
                )
            }
            "==", "!=", "<", "<=", ">", ">=" -> {
                val cmp = compare(left, right) ?: return IoTValue.Unknown
                IoTValue.BooleanValue(
                    when (e.operator) {
                        "==" -> cmp == 0
                        "!=" -> cmp != 0
                        "<" -> cmp < 0
                        "<=" -> cmp <= 0
                        ">" -> cmp > 0
                        else -> cmp >= 0
                    }
                )
            }
            "&", "|" -> {
                if (left is IoTValue.Number && right is IoTValue.Number) {
                    val a = left.value.toLong()
                    val b = right.value.toLong()
                    IoTValue.Number(if (e.operator == "&") (a and b).toDouble() else (a or b).toDouble())
                } else {
                    val a = left.asBoolean()
                    val b = right.asBoolean()
                    IoTValue.BooleanValue(if (e.operator == "&") a && b else a || b)
                }
            }
            else -> IoTValue.Unknown
        }
    }

    private fun builtin(name: String): IoTValue {
        val now = java.time.LocalDateTime.now()
        return when (name.lowercase()) {
            "gethours" -> IoTValue.Number(now.hour.toDouble())
            "getminutes" -> IoTValue.Number(now.minute.toDouble())
            "getseconds" -> IoTValue.Number(now.second.toDouble())
            "getmonth" -> IoTValue.Number(now.monthValue.toDouble())
            "getday" -> IoTValue.Number(now.dayOfMonth.toDouble())
            "gethhmm" -> IoTValue.Number((now.hour * 100 + now.minute).toDouble())
            "gethhmmss" -> IoTValue.Number((now.hour * 10000 + now.minute * 100 + now.second).toDouble())
            else -> IoTValue.Unknown
        }
    }

    private fun compare(left: IoTValue, right: IoTValue): Int? {
        if (left == IoTValue.Unknown || right == IoTValue.Unknown) return null
        val a = left.asNumber()
        val b = right.asNumber()
        if (a != null && b != null) return a.compareTo(b)
        val asText = left.asText()
        val bsText = right.asText()
        return if (asText != null && bsText != null) asText.compareTo(bsText) else null
    }

    private fun IoTValue.asNumber(): Double? = (this as? IoTValue.Number)?.value
    private fun IoTValue.asText(): String? = when (this) {
        is IoTValue.Number -> value.toString()
        is IoTValue.Text -> value
        is IoTValue.BooleanValue -> value.toString()
        IoTValue.Unknown -> null
    }
    private fun IoTValue.asBoolean(): Boolean = when (this) {
        is IoTValue.BooleanValue -> value
        is IoTValue.Number -> kotlin.math.abs(value) > 0.000001
        is IoTValue.Text -> value.isNotBlank() && value != "0" && !value.equals("false", true)
        IoTValue.Unknown -> false
    }
}

fun IoTValue.isTruthy(): Boolean = when (this) {
    is IoTValue.BooleanValue -> value
    is IoTValue.Number -> kotlin.math.abs(value) > 0.000001
    is IoTValue.Text -> value.isNotBlank() && value != "0" && !value.equals("false", true)
    IoTValue.Unknown -> false
}

data class ScenarioPrerequisite(
    val deviceId: String,
    val widgetId: String,
    val value: String,
    val reason: String
)

data class ScenarioCommandPlan(
    val actions: List<LocalCommandActionItem>,
    val prerequisites: List<ScenarioPrerequisite> = emptyList(),
    val blockedReason: String? = null
)

object IoTScenarioCommandPlanner {
    private data class Requirement(
        val widgetName: String,
        val value: String
    )

    fun plan(
        targetDeviceId: String,
        targetWidgetId: String,
        desiredValue: String,
        baseActions: List<LocalCommandActionItem>,
        devices: List<com.yasn198020.aicontrol.core.Device>,
        models: List<Pair<StoredDeviceScenario, DeviceScenarioModel>>
    ): ScenarioCommandPlan {
        val base = baseActions.ifEmpty {
            listOf(LocalCommandActionItem(targetDeviceId, targetWidgetId, desiredValue))
        }

        fun resolveWidgets(ids: Set<String>): Map<String, Pair<String, com.yasn198020.aicontrol.core.WidgetState>> {
            val all = devices.flatMap { device ->
                device.widgets.map { widget -> widget.id to (device.id to widget) }
            }
            return ids.mapNotNull { id ->
                val matches = all.filter { it.first == id }.map { it.second }
                val preferred = matches.firstOrNull { it.first == targetDeviceId }
                val selected = preferred ?: matches.singleOrNull()
                selected?.let { id to it }
            }.toMap()
        }

        val allModels = models.mapNotNull { (stored, model) ->
            if (model.parserErrors.isNotEmpty()) null else stored to model
        }

        /*
         * First try the generic dependency planner. This remains the fallback
         * for ordinary scenarios such as: if MODE == 1 then DOOR = 1.
         */
        val directCandidates = allModels.flatMap { (stored, model) ->
            val ids = (stored.sensorIds + model.identifiers).toSet()
            val resolved = resolveWidgets(ids)
            if (resolved.isEmpty()) return@flatMap emptyList<List<ScenarioPrerequisite>>()

            val variables = resolved.mapValues { it.value.second.value }
            val context = IoTScenarioEvaluationContext(variables)

            model.rules.mapNotNull { rule ->
                val matchingAction = rule.actions.firstOrNull {
                    it.targetId == targetWidgetId &&
                        valueMatchesDesired(it.expression, desiredValue, context)
                } ?: return@mapNotNull null

                val targetResolved = resolved[targetWidgetId]
                    ?: return@mapNotNull null
                if (targetResolved.first != targetDeviceId) return@mapNotNull null

                if (IoTScenarioEvaluator.evaluate(rule.condition.expression, context).isTruthy()) {
                    return@mapNotNull emptyList<ScenarioPrerequisite>()
                }

                deriveRequirementCandidates(rule.condition.expression, context)
                    .mapNotNull { requirements ->
                        requirements.mapNotNull { requirement ->
                            val resolvedWidget = resolved[requirement.widgetName] ?: return@mapNotNull null
                            val widget = resolvedWidget.second
                            if (!isControllable(widget)) return@mapNotNull null
                            val current = normalizeValue(widget.value)
                            if (valuesEquivalent(current, normalizeValue(requirement.value))) {
                                null
                            } else {
                                ScenarioPrerequisite(
                                    deviceId = resolvedWidget.first,
                                    widgetId = widget.id,
                                    value = requirement.value,
                                    reason = humanRequirement(widget, requirement.value)
                                )
                            }
                        }.distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }
                    }
                    .mapNotNull { prerequisites ->
                        val simulated = variables.toMutableMap()
                        prerequisites.forEach { simulated[it.widgetId] = it.value }
                        val after = IoTScenarioEvaluator.evaluate(
                            rule.condition.expression,
                            IoTScenarioEvaluationContext(simulated)
                        )
                        if (after.isTruthy()) prerequisites else null
                    }
                    .minByOrNull { it.size }
            }
        }

        /*
         * IoTManager control widgets are often state variables, while the real
         * actuator is a different ButtonOut. In that case the command must be
         * resolved backwards:
         *
         *     state command -> scenario condition -> real relay
         *
         * For the door in export.json this gives:
         *     vbtn78=1 -> btn43=1
         *     vbtn78=0 -> btn42=1
         *
         * A temperature comparison is deliberately not converted into a write.
         * A controllable mode variable such as vbtn90 is, however, a real
         * prerequisite when the manual branch requires it.
         */
        val reverseCandidates = buildReverseActuatorPlans(
            targetDeviceId = targetDeviceId,
            targetWidgetId = targetWidgetId,
            desiredValue = desiredValue,
            base = base,
            devices = devices,
            models = allModels,
            resolveWidgets = ::resolveWidgets
        )

        val selectedPrerequisites = chooseUniquePlan(directCandidates + reverseCandidates)
        if (selectedPrerequisites == null && directCandidates.isEmpty() && reverseCandidates.isEmpty()) {
            return ScenarioCommandPlan(base)
        }

        if (selectedPrerequisites == null) {
            return ScenarioCommandPlan(
                actions = base,
                blockedReason = "Не удалось однозначно определить зависимость команды: найдено несколько подходящих условий."
            )
        }

        val prerequisites = selectedPrerequisites
        val actuator = prerequisites.firstOrNull {
            it.deviceId == targetDeviceId &&
                it.value == "1" &&
                isActuatorId(it.widgetId, desiredValue, devices)
        }
        val realPrerequisites = prerequisites.filterNot { it === actuator }

        val actions = buildList {
            addAll(realPrerequisites.map { LocalCommandActionItem(it.deviceId, it.widgetId, it.value) })
            addAll(base)
            actuator?.let {
                add(LocalCommandActionItem(it.deviceId, it.widgetId, it.value))
            }
        }.distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }

        DiagnosticTrace.system(
            "MARFA dependency plan target=" + targetWidgetId +
                " prerequisites=" + prerequisites.joinToString(",") { it.widgetId + "=" + it.value } +
                " actions=" + actions.joinToString(",") { it.widgetId + "=" + it.value }
        )

        return ScenarioCommandPlan(actions, prerequisites)
    }

    private fun buildReverseActuatorPlans(
        targetDeviceId: String,
        targetWidgetId: String,
        desiredValue: String,
        base: List<LocalCommandActionItem>,
        devices: List<com.yasn198020.aicontrol.core.Device>,
        models: List<Pair<StoredDeviceScenario, DeviceScenarioModel>>,
        resolveWidgets: (Set<String>) -> Map<String, Pair<String, com.yasn198020.aicontrol.core.WidgetState>>
    ): List<List<ScenarioPrerequisite>> {
        val targetDesired = normalizeValue(desiredValue)
        if (targetDesired != "0" && targetDesired != "1") return emptyList()

        return models.flatMap { (stored, model) ->
            val ids = (stored.sensorIds + model.identifiers).toSet()
            val resolved = resolveWidgets(ids)
            val target = resolved[targetWidgetId] ?: return@flatMap emptyList<List<ScenarioPrerequisite>>()
            if (target.first != targetDeviceId) return@flatMap emptyList<List<ScenarioPrerequisite>>()

            val variables = resolved.mapValues { it.value.second.value }
            val context = IoTScenarioEvaluationContext(variables)
            val result = mutableListOf<List<ScenarioPrerequisite>>()

            model.rules.forEach { rule ->
                rule.actions.forEach { action ->
                    if (!isActuatorAction(action, targetDesired, resolved)) return@forEach

                    val targetCondition = findEquality(rule.condition.expression, targetWidgetId)
                        ?: return@forEach

                    /*
                     * We need the post-state branch for a state widget:
                     *   vbtn78 == 1 -> btn43 = 1
                     * and
                     *   vbtn78 == 0 -> btn42 = 1.
                     *
                     * The opposite/pre-state branch is not useful after the
                     * requested state has already been written.
                     */
                    if (normalizeValue(targetCondition) != targetDesired) return@forEach

                    val requirements = deriveRequirementCandidates(
                        removeEquality(rule.condition.expression, targetWidgetId),
                        context
                    )

                    requirements.forEach { candidate ->
                        val filtered = candidate.mapNotNull { req ->
                            val resolvedWidget = resolved[req.widgetName] ?: return@mapNotNull null
                            val widget = resolvedWidget.second
                            if (!isControllable(widget)) return@mapNotNull null

                            val current = normalizeValue(widget.value)
                            if (valuesEquivalent(current, normalizeValue(req.value))) {
                                null
                            } else {
                                ScenarioPrerequisite(
                                    deviceId = resolvedWidget.first,
                                    widgetId = widget.id,
                                    value = req.value,
                                    reason = humanRequirement(widget, req.value)
                                )
                            }
                        }.distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }

                        /*
                         * Validate the commandable part of the branch only.
                         * IoTManager may also contain sensor/internal predicates
                         * (temperature, helper variables, timers). Those are
                         * runtime conditions and must never block the voice plan
                         * or become MQTT writes.
                         */
                        result += filtered + ScenarioPrerequisite(
                            deviceId = actionTargetDeviceId(action, resolved, targetDeviceId),
                            widgetId = action.targetId,
                            value = literalValue(action.expression) ?: targetDesired,
                            reason = "Сценарий: после установки «" +
                                (resolved[targetWidgetId]?.second?.title?.ifBlank { targetWidgetId } ?: targetWidgetId) +
                                "» срабатывает «" +
                                (resolved[action.targetId]?.second?.title?.ifBlank { action.targetId } ?: action.targetId) +
                                "»"
                        )
                    }
                }
            }
            result
        }
    }

    private fun chooseUniquePlan(candidates: List<List<ScenarioPrerequisite>>): List<ScenarioPrerequisite>? {
        if (candidates.isEmpty()) return null
        val unique = candidates
            .map { it.distinctBy { p -> p.deviceId + "/" + p.widgetId + "/" + p.value } }
            .distinctBy { it.joinToString("|") { p -> p.deviceId + "/" + p.widgetId + "=" + p.value } }

        val minCount = unique.minOf { it.size }
        val best = unique.filter { it.size == minCount }
        return if (best.size == 1) best.single() else null
    }

    private fun List<ScenarioPrerequisite>.containsActuatorFor(
        targetDeviceId: String,
        desiredValue: String,
        devices: List<com.yasn198020.aicontrol.core.Device>
    ): Boolean =
        any { it.deviceId == targetDeviceId && it.value == "1" && isActuatorWidget(it.widgetId, desiredValue, devices) }

    private fun isActuatorAction(
        action: DeviceScenarioAction,
        desiredValue: String,
        resolved: Map<String, Pair<String, com.yasn198020.aicontrol.core.WidgetState>>
    ): Boolean {
        val value = literalValue(action.expression) ?: return false
        if (value != "1") return false
        val widget = resolved[action.targetId]?.second ?: return false
        return isActuatorWidget(widget, desiredValue)
    }

    private fun isActuatorId(
        widgetId: String,
        desiredValue: String,
        devices: List<com.yasn198020.aicontrol.core.Device>
    ): Boolean =
        devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .firstOrNull { it.id == widgetId }
            ?.let { isActuatorWidget(it, desiredValue) }
            ?: false

    private fun isActuatorWidget(
        widget: com.yasn198020.aicontrol.core.WidgetState,
        desiredValue: String
    ): Boolean {
        val title = widget.title.lowercase()
        val isOutput = widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.TOGGLE ||
            widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.BUTTON
        if (!isOutput) return false
        val open = listOf("откры", "open", "подним", "распах", "отпер").any { title.contains(it) }
        val close = listOf("закры", "close", "опуст", "запер").any { title.contains(it) }
        return if (desiredValue == "1") open && !close else close && !open
    }

    private fun actionTargetDeviceId(
        action: DeviceScenarioAction,
        resolved: Map<String, Pair<String, com.yasn198020.aicontrol.core.WidgetState>>,
        fallback: String
    ): String = resolved[action.targetId]?.first ?: fallback

    private fun findEquality(expression: IoTExpr, variable: String): String? = when (expression) {
        is IoTExpr.Binary -> {
            if (expression.operator == "==" ) {
                when {
                    expression.left is IoTExpr.Variable && (expression.left as IoTExpr.Variable).name == variable ->
                        literalValue(expression.right)
                    expression.right is IoTExpr.Variable && (expression.right as IoTExpr.Variable).name == variable ->
                        literalValue(expression.left)
                    else -> findEquality(expression.left, variable) ?: findEquality(expression.right, variable)
                }
            } else findEquality(expression.left, variable) ?: findEquality(expression.right, variable)
        }
        is IoTExpr.Unary -> findEquality(expression.expression, variable)
        else -> null
    }

    private fun removeEquality(expression: IoTExpr, variable: String): IoTExpr =
        when (expression) {
            is IoTExpr.Binary -> {
                if (expression.operator == "&") {
                    val leftHas = findEquality(expression.left, variable) != null
                    val rightHas = findEquality(expression.right, variable) != null
                    when {
                        leftHas && !rightHas -> expression.right
                        !leftHas && rightHas -> expression.left
                        leftHas && rightHas -> IoTExpr.NumberLiteral(1.0)
                        else -> IoTExpr.Binary("&", removeEquality(expression.left, variable), removeEquality(expression.right, variable))
                    }
                } else expression
            }
            else -> IoTExpr.NumberLiteral(1.0)
        }

    private fun isControllable(
        widget: com.yasn198020.aicontrol.core.WidgetState
    ): Boolean =
        widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.TOGGLE ||
            widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.BUTTON ||
            widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.INPUT

    private fun humanRequirement(
        widget: com.yasn198020.aicontrol.core.WidgetState,
        value: String
    ): String {
        val title = widget.title.trim().ifBlank { widget.id }
        return "«" + title + "» должно быть " + value
    }

    private fun normalizeValue(value: String): String =
        value.trim().replace(',', '.')

    private fun valuesEquivalent(actual: String, expected: String): Boolean {
        val a = normalizeValue(actual).toDoubleOrNull()
        val e = normalizeValue(expected).toDoubleOrNull()
        return if (a != null && e != null) {
            kotlin.math.abs(a - e) < 0.000001
        } else {
            actual.trim() == expected.trim()
        }
    }

    private fun deriveRequirementCandidates(
        expression: IoTExpr,
        context: IoTScenarioEvaluationContext
    ): List<List<Requirement>> {
        if (IoTScenarioEvaluator.evaluate(expression, context).isTruthy()) {
            return listOf(emptyList())
        }

        fun variableAndLiteral(left: IoTExpr, right: IoTExpr): Pair<String, String>? {
            val variable = left as? IoTExpr.Variable ?: return null
            val literal = literalValue(right) ?: return null
            return variable.name to literal
        }

        fun visit(expr: IoTExpr): List<List<Requirement>> = when (expr) {
            is IoTExpr.Binary -> when (expr.operator) {
                "&" -> {
                    val left = visit(expr.left)
                    val right = visit(expr.right)
                    if (left.isEmpty() || right.isEmpty()) emptyList()
                    else left.flatMap { l -> right.map { r -> (l + r).distinctBy { it.widgetName + "=" + it.value } } }
                }
                "|" -> visit(expr.left) + visit(expr.right)
                "==" -> {
                    val pair = variableAndLiteral(expr.left, expr.right)
                        ?: variableAndLiteral(expr.right, expr.left)
                    pair?.let { listOf(listOf(Requirement(it.first, it.second))) }.orEmpty()
                }
                "!=" -> {
                    val pair = variableAndLiteral(expr.left, expr.right)
                        ?: variableAndLiteral(expr.right, expr.left)
                    if (pair == null) emptyList()
                    else {
                        val current = context.variables[pair.first]?.trim()?.replace(',', '.')
                        val required = pair.second.trim().replace(',', '.')
                        if (current == null || !valuesEquivalent(current, required)) listOf(emptyList())
                        else {
                            val opposite = when (required.toDoubleOrNull()) {
                                0.0 -> "1"
                                1.0 -> "0"
                                else -> null
                            }
                            opposite?.let { listOf(listOf(Requirement(pair.first, it))) }.orEmpty()
                        }
                    }
                }
                else -> emptyList()
            }
            is IoTExpr.Unary -> {
                if (expr.operator != "!") emptyList()
                else when (val inner = expr.expression) {
                    is IoTExpr.Variable -> {
                        val current = context.variables[inner.name]?.trim()?.replace(',', '.')
                        if (current.isNullOrBlank()) emptyList()
                        else if (isFalseValue(current)) listOf(emptyList())
                        else listOf(listOf(Requirement(inner.name, "0")))
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }

        return visit(expression)
            .map { it.distinctBy { req -> req.widgetName + "=" + req.value } }
            .distinctBy { it.joinToString("|") { req -> req.widgetName + "=" + req.value } }
    }

    private fun isFalseValue(value: String): Boolean {
        val numeric = value.toDoubleOrNull()
        return numeric != null && kotlin.math.abs(numeric) < 0.000001 ||
            value.equals("false", ignoreCase = true) ||
            value.isBlank()
    }

    private fun literalValue(expression: IoTExpr): String? = when (expression) {
        is IoTExpr.NumberLiteral ->
            java.math.BigDecimal.valueOf(expression.value).stripTrailingZeros().toPlainString()
        is IoTExpr.StringLiteral -> expression.value
        is IoTExpr.Unary ->
            if (expression.operator == "-") {
                literalValue(expression.expression)?.toBigDecimalOrNull()?.negate()?.stripTrailingZeros()?.toPlainString()
            } else null
        else -> null
    }

    private fun valueMatchesDesired(
        expr: IoTExpr,
        desired: String,
        context: IoTScenarioEvaluationContext
    ): Boolean {
        val expected = IoTScenarioEvaluator.evaluate(expr, context)
        val e = (expected as? IoTValue.Number)?.value
        val d = desired.replace(',', '.').toDoubleOrNull()
        return if (e != null && d != null) kotlin.math.abs(e - d) < 0.000001
        else (expected as? IoTValue.Text)?.value == desired
    }
}

object DeviceScenarioModelFormatter {
    fun summary(model: DeviceScenarioModel): String {
        if (model.parserErrors.isNotEmpty()) return "Ошибки разбора: " + model.parserErrors.take(5).joinToString("; ")
        if (model.rules.isEmpty()) return "Правила не найдены"
        val lines = mutableListOf<String>()
        lines += "Найдено правил: " + model.rules.size
        model.rules.take(12).forEach { rule ->
            lines += "ЕСЛИ " + rule.condition.rendered + " → " +
                rule.actions.joinToString("; ") { it.rendered }
        }
        if (model.rules.size > 12) lines += "… ещё " + (model.rules.size - 12)
        return lines.joinToString("\n")
    }

    fun tree(
        model: DeviceScenarioModel,
        labels: Map<String, String> = emptyMap()
    ): String {
        if (model.parserErrors.isNotEmpty()) {
            return "Дерево недоступно: сначала исправьте ошибки разбора.\n" +
                model.parserErrors.take(5).joinToString("\n")
        }
        if (model.statements.isEmpty()) return "Дерево пустое."

        val lines = mutableListOf<String>()

        fun labelFor(id: String): String =
            labels[id]?.trim()?.takeIf { it.isNotBlank() } ?: id

        fun valueText(expression: IoTExpr): String? = when (expression) {
            is IoTExpr.NumberLiteral ->
                java.math.BigDecimal.valueOf(expression.value).stripTrailingZeros().toPlainString()
            is IoTExpr.StringLiteral -> expression.value
            else -> null
        }

        fun semanticActionName(label: String, expression: IoTExpr): String? {
            val l = label.lowercase()
            val value = valueText(expression) ?: return null
            if (l.contains("форточ") && (l.contains("закрыта") || l.contains("открыта"))) {
                return if (value == "1") "Открыть форточку" else if (value == "0") "Закрыть форточку" else null
            }
            if (l.contains("двер") && (l.contains("закрыта") || l.contains("открыта"))) {
                return if (value == "1") "Открыть дверь" else if (value == "0") "Закрыть дверь" else null
            }
            return null
        }

        fun actionText(statement: IoTStatement.Assignment): String {
            val label = labelFor(statement.target)
            val value = valueText(statement.expression)
            val semanticName = semanticActionName(label, statement.expression)

            if (semanticName != null && label.lowercase().let { it.contains("закрыта") || it.contains("открыта") }) {
                val state = if (value == "1") "открыта" else if (value == "0") "закрыта" else value
                return "ДЕЙСТВИЕ: $semanticName → установить состояние «$label» = $state"
            }
            if (value == "1") {
                if (semanticName != null) {
                    return "ДЕЙСТВИЕ: $semanticName → включить «$label»"
                }
                return "ДЕЙСТВИЕ: включить «$label»"
            }
            if (value == "0") {
                if (semanticName != null) {
                    return "ДЕЙСТВИЕ: $semanticName → выключить «$label»"
                }
                return "ДЕЙСТВИЕ: выключить «$label»"
            }

            return "ДЕЙСТВИЕ: установить «$label» = " + statement.expression.render()
        }

        fun appendStatement(statement: IoTStatement, prefix: String, branch: String) {
            val linePrefix = if (branch.isEmpty()) "" else prefix + branch
            when (statement) {
                is IoTStatement.If -> {
                    lines += linePrefix + "ЕСЛИ " + statement.condition.render(labels)
                    appendStatement(
                        statement.thenBranch,
                        prefix + if (branch.isEmpty()) "    " else "│   ",
                        "├─ "
                    )
                    statement.elseBranch?.let {
                        lines += prefix + if (branch.isEmpty()) "" else "│   " + "ИНАЧЕ"
                        appendStatement(
                            it,
                            prefix + if (branch.isEmpty()) "    " else "    ",
                            "└─ "
                        )
                    }
                }
                is IoTStatement.Assignment -> {
                    lines += linePrefix + actionText(statement)
                }
                is IoTStatement.ExpressionStatement -> {
                    lines += linePrefix + "ВЫЗОВ: " + statement.expression.render(labels)
                }
                is IoTStatement.Block -> {
                    if (statement.statements.isEmpty()) {
                        lines += linePrefix + "БЛОК: пусто"
                    } else {
                        statement.statements.forEachIndexed { index, child ->
                            val childBranch =
                                if (index == statement.statements.lastIndex) "└─ " else "├─ "
                            appendStatement(child, prefix, childBranch)
                        }
                    }
                }
            }
        }

        model.statements.forEachIndexed { index, statement ->
            appendStatement(
                statement,
                "",
                if (model.statements.size == 1) ""
                else if (index == model.statements.lastIndex) "└─ "
                else "├─ "
            )
        }

        return lines.joinToString("\n")
    }

    fun actionOverview(
        model: DeviceScenarioModel,
        labels: Map<String, String> = emptyMap()
    ): String {
        if (model.parserErrors.isNotEmpty()) return ""

        fun labelFor(id: String): String =
            labels[id]?.trim()?.takeIf { it.isNotBlank() } ?: id

        fun valueText(expression: IoTExpr): String? = when (expression) {
            is IoTExpr.NumberLiteral ->
                java.math.BigDecimal.valueOf(expression.value).stripTrailingZeros().toPlainString()
            is IoTExpr.StringLiteral -> expression.value
            else -> null
        }

        fun conditionText(expression: IoTExpr): String =
            expression.render(labels)

        fun actionText(statement: IoTStatement.Assignment): String {
            val label = labelFor(statement.target)
            val value = valueText(statement.expression)
            val lower = label.lowercase()

            val semanticName = when {
                lower.contains("форточ") && (lower.contains("закрыта") || lower.contains("открыта")) ->
                    if (value == "1") "Открыть форточку" else if (value == "0") "Закрыть форточку" else null
                lower.contains("двер") && (lower.contains("закрыта") || lower.contains("открыта")) ->
                    if (value == "1") "Открыть дверь" else if (value == "0") "Закрыть дверь" else null
                else -> null
            }

            val isState = lower.contains("закрыта") || lower.contains("открыта")
            return when {
                semanticName != null && isState ->
                    "$semanticName → установить состояние «$label» = " +
                        if (value == "1") "открыта" else if (value == "0") "закрыта" else value
                semanticName != null && value == "1" ->
                    "$semanticName → включить «$label»"
                value == "1" ->
                    "включить «$label»"
                value == "0" ->
                    "выключить «$label»"
                else ->
                    "установить «$label» = " + statement.expression.render(labels)
            }
        }

        fun collectAssignments(
            statement: IoTStatement,
            condition: String?,
            out: MutableList<Pair<String?, String>>
        ) {
            when (statement) {
                is IoTStatement.If -> {
                    val ownCondition = conditionText(statement.condition)
                    collectAssignments(statement.thenBranch, ownCondition, out)
                    statement.elseBranch?.let {
                        collectAssignments(it, "НЕ ($ownCondition)", out)
                    }
                }
                is IoTStatement.Block -> statement.statements.forEach {
                    collectAssignments(it, condition, out)
                }
                is IoTStatement.Assignment -> {
                    out += condition to actionText(statement)
                }
                is IoTStatement.ExpressionStatement -> Unit
            }
        }

        val entries = mutableListOf<Pair<String?, String>>()
        model.statements.forEach { collectAssignments(it, null, entries) }
        if (entries.isEmpty()) return ""

        return buildString {
            entries.forEachIndexed { index, (condition, action) ->
                if (index > 0) append("\n")
                append(action)
                if (!condition.isNullOrBlank()) {
                    append("\n  → ЗАВИСИМОСТЬ: ")
                    append(condition)
                }
            }
        }.trim()
    }
}
