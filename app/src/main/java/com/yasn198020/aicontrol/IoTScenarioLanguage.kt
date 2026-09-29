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
    val parserErrors: List<String>
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
                        if (ch.isLetterOrDigit() || ch == '_' || ch == ':' || ch == '.') i++ else break
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
        val lexed = IoTScenarioLexer.tokenize(source)
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
        return DeviceScenarioModel(rules, parsed.referencedIdentifiers, parsed.errors)
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
                "!" -> IoTValue.BooleanValue(!value.asBoolean())
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
                val cmp = compare(left, right)
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

    private fun compare(left: IoTValue, right: IoTValue): Int {
        val a = left.asNumber()
        val b = right.asNumber()
        if (a != null && b != null) return a.compareTo(b)
        val asText = left.asText()
        val bsText = right.asText()
        return if (asText != null && bsText != null) asText.compareTo(bsText) else 1
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
    fun plan(
        targetDeviceId: String,
        targetWidgetId: String,
        desiredValue: String,
        baseActions: List<LocalCommandActionItem>,
        devices: List<com.yasn198020.aicontrol.core.Device>,
        models: List<Pair<String, DeviceScenarioModel>>
    ): ScenarioCommandPlan {
        val base = baseActions.ifEmpty { listOf(LocalCommandActionItem(targetDeviceId, targetWidgetId, desiredValue)) }
        val context = IoTScenarioEvaluationContext(devices.flatMap { it.widgets }.associate { it.id to it.value })

        val candidates = models.flatMap { (scenarioDeviceId, model) ->
            model.rules.filter { rule ->
                rule.actions.any { action ->
                    action.targetId == targetWidgetId && valueMatchesDesired(action.expression, desiredValue, context)
                }
            }.map { scenarioDeviceId to it }
        }
        if (candidates.isEmpty()) return ScenarioCommandPlan(base)

        val satisfiable = candidates.map { pair ->
            val prerequisites = extractEqualityPrerequisites(pair.second.condition.expression).mapNotNull { (name, value) ->
                val ownerWidget = devices.flatMap { it.widgets.map { w -> it to w } }.firstOrNull { it.second.id == name }
                    ?: return@mapNotNull null
                val device = ownerWidget.first
                val widget = ownerWidget.second
                val controllable = widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.TOGGLE ||
                    widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.BUTTON ||
                    widget.type == com.yasn198020.aicontrol.core.WidgetState.Type.INPUT
                if (!controllable) return@mapNotNull null
                val current = widget.value.replace(',', '.').toDoubleOrNull()
                if (current != null && kotlin.math.abs(current - value.toDouble()) < 0.000001) return@mapNotNull null
                ScenarioPrerequisite(
                    device.id,
                    widget.id,
                    value.toPlainString(),
                    name + " должно быть " + value.toPlainString()
                )
            }
            pair.second to prerequisites
        }
        val best = satisfiable.minByOrNull { it.second.size } ?: return ScenarioCommandPlan(base)
        val prerequisites = best.second.distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }
        val actions = (prerequisites.map { LocalCommandActionItem(it.deviceId, it.widgetId, it.value) } + base)
            .distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }
        return ScenarioCommandPlan(actions, prerequisites)
    }

    private fun valueMatchesDesired(expr: IoTExpr, desired: String, context: IoTScenarioEvaluationContext): Boolean {
        val expected = IoTScenarioEvaluator.evaluate(expr, context)
        val e = (expected as? IoTValue.Number)?.value
        val d = desired.replace(',', '.').toDoubleOrNull()
        return if (e != null && d != null) kotlin.math.abs(e - d) < 0.000001
        else (expected as? IoTValue.Text)?.value == desired
    }

    private fun extractEqualityPrerequisites(expr: IoTExpr): List<Pair<String, java.math.BigDecimal>> {
        val out = mutableListOf<Pair<String, java.math.BigDecimal>>()
        fun visit(e: IoTExpr) {
            when (e) {
                is IoTExpr.Binary -> {
                    if (e.operator == "&") {
                        visit(e.left); visit(e.right)
                    } else if (e.operator == "==") {
                        val name = (e.left as? IoTExpr.Variable)?.name
                        val value = literalNumber(e.right)
                        if (name != null && value != null) out += name to value
                    }
                }
                else -> Unit
            }
        }
        visit(expr)
        return out
    }

    private fun literalNumber(expr: IoTExpr): java.math.BigDecimal? = when (expr) {
        is IoTExpr.NumberLiteral -> expr.value.toString().toBigDecimalOrNull()
        is IoTExpr.Unary -> if (expr.operator == "-") literalNumber(expr.expression)?.negate() else null
        else -> null
    }
}

object DeviceScenarioModelFormatter {
    fun summary(model: DeviceScenarioModel): String {
        if (model.parserErrors.isNotEmpty()) return "Ошибки разбора: " + model.parserErrors.take(5).joinToString("; ")
        if (model.rules.isEmpty()) return "Правила не найдены"
        val lines = mutableListOf<String>()
        lines += "Найдено правил: " + model.rules.size
        model.rules.take(12).forEach { rule ->
            lines += "ЕСЛИ " + rule.condition.rendered + " → " + rule.actions.joinToString("; ") { it.rendered }
        }
        if (model.rules.size > 12) lines += "… ещё " + (model.rules.size - 12)
        return lines.joinToString("\n")
    }
}
