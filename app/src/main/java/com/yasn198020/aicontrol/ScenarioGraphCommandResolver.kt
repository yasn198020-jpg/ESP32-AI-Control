package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState

/**
 * Scenario graph decides the real controller.
 *
 * Walk from the requested/physical element upward:
 * controller -> affected element.
 *
 * Sensors, measurements, feedback and mode gates are not controllers.
 * If a controller is itself controlled by another controller, it is only an
 * intermediate node and the walk continues upward.
 *
 * If no real controller remains, the original element stays the target.
 * This is required for a relay which nobody controls through the scenario.
 *
 * No device, widget ID or object name is special-cased.
 */
data class ScenarioGraphCommandResolution(
    val target: MarfaAnalyticalEngine.ControlCandidate? = null,
    val prerequisites: List<ScenarioPrerequisite> = emptyList(),
    val blockedReason: String? = null,
    val resolvedByScenario: Boolean = false,
    val alternatives: List<MarfaAnalyticalEngine.ControlCandidate> = emptyList()
)

class ScenarioGraphCommandResolver {

    private data class Node(
        val device: Device,
        val widget: WidgetState
    )

    private data class Path(
        val candidate: MarfaAnalyticalEngine.ControlCandidate,
        val top: Node,
        val chain: List<String>,
        val strong: Boolean
    )

    fun resolve(
        candidates: List<MarfaAnalyticalEngine.ControlCandidate>,
        desiredValue: String,
        devices: List<Device>,
        models: List<Pair<StoredDeviceScenario, DeviceScenarioModel>>
    ): ScenarioGraphCommandResolution {
        if (candidates.isEmpty()) return ScenarioGraphCommandResolution()

        val nodes = uniqueNodes(devices)
        if (nodes.isEmpty()) return ScenarioGraphCommandResolution()

        val enabledModels = models.filter { it.first.enabled && it.second.valid }
        if (enabledModels.isEmpty()) return directFallback(candidates)

        val actionTargets = linkedSetOf<String>()
        val parents = linkedMapOf<String, MutableSet<String>>()
        val rulesByTarget = linkedMapOf<String, MutableList<DeviceScenarioRule>>()

        enabledModels.forEach { (_, model) ->
            model.rules.forEach { rule ->
                rule.actions.forEach { action ->
                    actionTargets += action.targetId
                    rulesByTarget.getOrPut(action.targetId) { mutableListOf() } += rule

                    rule.condition.identifiers.forEach { id ->
                        if (id.isNotBlank() && id != action.targetId) {
                            parents.getOrPut(action.targetId) { linkedSetOf() }.add(id)
                        }
                    }
                }
            }
        }

        val ambiguousControllers = linkedMapOf<String, Node>()

        val paths = candidates.mapNotNull { candidate ->
            val start = nodes[candidate.widget.id] ?: return@mapNotNull null
            if (isObservationWidget(start.widget)) return@mapNotNull null

            val topPaths = findTopPaths(
                startId = start.widget.id,
                parents = parents,
                rulesByTarget = rulesByTarget,
                nodes = nodes
            )

            if (topPaths.isEmpty()) return@mapNotNull null

            if (topPaths.size > 1) {
                topPaths.mapNotNull { nodes[it.topId] }
                    .forEach { node -> ambiguousControllers[node.widget.id] = node }
                return@mapNotNull null
            }

            val topPath = topPaths.single()
            val top = nodes[topPath.topId] ?: return@mapNotNull null
            Path(
                candidate = candidate,
                top = top,
                chain = topPath.chain,
                strong = top.widget.id in actionTargets
            )
        }

        if (ambiguousControllers.size > 1) {
            val candidateScores = candidates.associate { it.widget.id to it.score }
            val alternatives = ambiguousControllers.values
                .map { toCandidate(it, candidateScores[it.widget.id] ?: 0) }
            return ScenarioGraphCommandResolution(
                blockedReason = "Сценарий оставляет несколько независимых управляющих элементов: " +
                    alternatives.joinToString(" или ") { it.widget.title.ifBlank { it.widget.id } } + ".",
                resolvedByScenario = true,
                alternatives = alternatives
            )
        }

        if (paths.isEmpty()) return ScenarioGraphCommandResolution()

        val strongPaths = paths.filter { it.strong }
        val effectivePaths = if (strongPaths.isNotEmpty()) strongPaths else paths

        val grouped = effectivePaths.groupBy { it.top.device.id + "/" + it.top.widget.id }
        if (grouped.size > 1) {
            val alternatives = grouped.values.mapNotNull { group ->
                group.maxByOrNull { it.candidate.score }
                    ?.let { toCandidate(it.top, it.candidate.score) }
            }
            val labels = alternatives
                .map { it.widget.title.ifBlank { it.widget.id } }
                .distinct()

            return ScenarioGraphCommandResolution(
                blockedReason = "Сценарий оставляет несколько независимых управляющих элементов: " +
                    labels.joinToString(" или ") + ".",
                resolvedByScenario = true,
                alternatives = alternatives
            )
        }

        val path = grouped.values
            .firstOrNull()
            ?.maxByOrNull { it.candidate.score }
            ?: return directFallback(candidates)

        val blockers = findBlockers(
            chain = path.chain,
            parents = parents,
            rulesByTarget = rulesByTarget,
            nodes = nodes
        )

        if (blockers.blockedReason != null) {
            return ScenarioGraphCommandResolution(
                target = toCandidate(path.top, path.candidate.score),
                blockedReason = blockers.blockedReason,
                resolvedByScenario = true
            )
        }

        return ScenarioGraphCommandResolution(
            target = toCandidate(path.top, path.candidate.score),
            prerequisites = blockers.items,
            resolvedByScenario = true
        )
    }

    private fun directFallback(
        candidates: List<MarfaAnalyticalEngine.ControlCandidate>
    ): ScenarioGraphCommandResolution {
        val distinct = candidates.distinctBy { it.device.id + "/" + it.widget.id }
        if (distinct.size == 1) {
            return ScenarioGraphCommandResolution(target = distinct.single())
        }

        val best = distinct.maxByOrNull { it.score } ?: return ScenarioGraphCommandResolution()
        val tied = distinct.filter { it.score == best.score }
        return if (tied.size == 1) {
            ScenarioGraphCommandResolution(target = best)
        } else {
            ScenarioGraphCommandResolution(
                blockedReason = "Не удалось однозначно определить элемент управления.",
                alternatives = tied
            )
        }
    }

    private data class TopPath(
        val topId: String,
        val chain: List<String>
    )

    private fun findTopPaths(
        startId: String,
        parents: Map<String, Set<String>>,
        rulesByTarget: Map<String, List<DeviceScenarioRule>>,
        nodes: Map<String, Node>
    ): List<TopPath> {
        fun visit(
            currentId: String,
            chain: List<String>,
            visiting: Set<String>
        ): List<TopPath> {
            if (currentId !in nodes) return emptyList()
            if (currentId in visiting) return emptyList()

            val nextVisiting = visiting + currentId
            val controllerParents = parents[currentId]
                .orEmpty()
                .mapNotNull { id -> nodes[id]?.let { id to it } }
                .filter { (id, parent) ->
                    isController(parent.widget, currentId, id, rulesByTarget)
                }
                .distinctBy { it.first }

            if (controllerParents.isEmpty()) {
                return listOf(TopPath(currentId, chain))
            }

            return controllerParents
                .flatMap { (id, _) ->
                    visit(
                        currentId = id,
                        chain = chain + id,
                        visiting = nextVisiting
                    )
                }
                .distinctBy { it.topId }
        }

        return visit(
            currentId = startId,
            chain = emptyList(),
            visiting = emptySet()
        )
    }

    private fun isController(
        widget: WidgetState,
        affectedId: String,
        controllerId: String,
        rulesByTarget: Map<String, List<DeviceScenarioRule>>
    ): Boolean {
        if (!isControllable(widget)) return false
        if (isModeWidget(widget) || isObservationWidget(widget)) return false

        return rulesByTarget[affectedId].orEmpty().any { rule ->
            rule.condition.identifiers.contains(controllerId) &&
                hasDiscreteControlPredicate(rule.condition.expression, controllerId)
        }
    }

    private fun hasDiscreteControlPredicate(
        expression: IoTExpr,
        wanted: String
    ): Boolean {
        var found = false

        fun visit(node: IoTExpr) {
            if (found) return
            when (node) {
                is IoTExpr.Binary -> {
                    if (node.operator == "==" || node.operator == "!=") {
                        if (containsVariable(node.left, wanted) ||
                            containsVariable(node.right, wanted)
                        ) {
                            found = true
                            return
                        }
                    }
                    visit(node.left)
                    visit(node.right)
                }
                is IoTExpr.Unary -> visit(node.expression)
                is IoTExpr.Call -> node.args.forEach(::visit)
                is IoTExpr.Variable,
                is IoTExpr.NumberLiteral,
                is IoTExpr.StringLiteral -> Unit
            }
        }

        visit(expression)
        return found || (expression is IoTExpr.Variable && expression.name == wanted)
    }

    private fun containsVariable(expression: IoTExpr, wanted: String): Boolean =
        when (expression) {
            is IoTExpr.Variable -> expression.name == wanted
            is IoTExpr.Binary ->
                containsVariable(expression.left, wanted) ||
                    containsVariable(expression.right, wanted)
            is IoTExpr.Unary -> containsVariable(expression.expression, wanted)
            is IoTExpr.Call -> expression.args.any { containsVariable(it, wanted) }
            is IoTExpr.NumberLiteral,
            is IoTExpr.StringLiteral -> false
        }

    private data class BlockerResult(
        val items: List<ScenarioPrerequisite>,
        val blockedReason: String? = null
    )

    private fun findBlockers(
        chain: List<String>,
        desiredValue: String,
        parents: Map<String, Set<String>>,
        rulesByTarget: Map<String, List<DeviceScenarioRule>>,
        nodes: Map<String, Node>
    ): BlockerResult {
        val blockers = linkedMapOf<String, MutableSet<String>>()

        chain.forEach { affectedId ->
            rulesByTarget[affectedId].orEmpty().forEach { rule ->
                if (containsOperator(rule.condition.expression, "|")) return@forEach

                val controllerIds = parents[affectedId].orEmpty()
                    .filter { it in chain }
                    .toSet()

                rule.condition.identifiers
                    .filter { it !in controllerIds && it != affectedId }
                    .forEach { id ->
                        val node = nodes[id] ?: return@forEach
                        if (!isControllable(node.widget)) return@forEach

                        val requirements = equalityRequirements(rule.condition.expression, id)
                        if (requirements.isNotEmpty()) {
                            blockers.getOrPut(id) { linkedSetOf() }.addAll(requirements)
                        }
                    }
            }
        }

        if (blockers.isEmpty()) return BlockerResult(emptyList())

        val result = mutableListOf<ScenarioPrerequisite>()
        blockers.forEach { (id, requiredValues) ->
            val node = nodes[id] ?: return@forEach
            val distinct = requiredValues.map(::normalizeValue).distinct()

            if (distinct.size > 1) {
                return BlockerResult(
                    emptyList(),
                    "Для выполнения команды элемент «" +
                        node.widget.title.ifBlank { id } +
                        "» имеет несколько несовместимых условий сценария."
                )
            }

            val required = distinct.single()
            val actual = normalizeValue(node.widget.value)

            if (isModeWidget(node.widget)) {
                val manualValue = manualModeValue(node.widget, required)
                if (!valuesEquivalent(actual, manualValue)) {
                    result += ScenarioPrerequisite(
                        deviceId = node.device.id,
                        widgetId = id,
                        value = manualValue,
                        reason = "Блокирует команду: " +
                            node.widget.title.ifBlank { id } +
                            " должно быть " + manualValue
                    )
                }
            } else if (!valuesEquivalent(actual, required)) {
                result += ScenarioPrerequisite(
                    deviceId = node.device.id,
                    widgetId = id,
                    value = required,
                    reason = "Условие сценария: " +
                        node.widget.title.ifBlank { id } +
                        " должно быть " + required
                )
            }
        }

        return BlockerResult(
            result.distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }
        )
    }

    private fun containsOperator(expression: IoTExpr, wanted: String): Boolean =
        when (expression) {
            is IoTExpr.Binary ->
                expression.operator == wanted ||
                    containsOperator(expression.left, wanted) ||
                    containsOperator(expression.right, wanted)
            is IoTExpr.Unary -> containsOperator(expression.expression, wanted)
            is IoTExpr.Call -> expression.args.any { containsOperator(it, wanted) }
            is IoTExpr.Variable,
            is IoTExpr.NumberLiteral,
            is IoTExpr.StringLiteral -> false
        }

    private fun equalityRequirements(expression: IoTExpr, wanted: String): List<String> {
        val result = mutableListOf<String>()

        fun visit(node: IoTExpr) {
            when (node) {
                is IoTExpr.Binary -> {
                    if (node.operator == "==") {
                        val left = node.left as? IoTExpr.Variable
                        val right = literal(node.right)
                        if (left?.name == wanted && right != null) result += right

                        val rightVar = node.right as? IoTExpr.Variable
                        val leftLiteral = literal(node.left)
                        if (rightVar?.name == wanted && leftLiteral != null) result += leftLiteral
                    }
                    visit(node.left)
                    visit(node.right)
                }
                is IoTExpr.Unary -> visit(node.expression)
                is IoTExpr.Call -> node.args.forEach(::visit)
                is IoTExpr.Variable,
                is IoTExpr.NumberLiteral,
                is IoTExpr.StringLiteral -> Unit
            }
        }

        visit(expression)
        return result
    }

    private fun literal(expression: IoTExpr): String? =
        when (expression) {
            is IoTExpr.NumberLiteral ->
                expression.value.toString().removeSuffix(".0")
            is IoTExpr.StringLiteral -> expression.value
            is IoTExpr.Variable,
            is IoTExpr.Binary,
            is IoTExpr.Unary,
            is IoTExpr.Call -> null
        }

    private fun uniqueNodes(devices: List<Device>): Map<String, Node> {
        val grouped = devices
            .flatMap { device ->
                device.widgets.map { widget -> widget.id to Node(device, widget) }
            }
            .groupBy({ it.first }, { it.second })

        return grouped.mapNotNull { (id, entries) ->
            if (id.isBlank() || entries.size != 1) null else id to entries.single()
        }.toMap()
    }

    private fun isControllable(widget: WidgetState): Boolean =
        widget.type == WidgetState.Type.BUTTON ||
            widget.type == WidgetState.Type.TOGGLE ||
            widget.type == WidgetState.Type.INPUT

    private fun isObservationWidget(widget: WidgetState): Boolean {
        if (widget.type == WidgetState.Type.VALUE ||
            widget.type == WidgetState.Type.STATUS
        ) return true

        val text = EmojiSemanticText.normalize(
            widget.id + " " +
                widget.title + " " +
                widget.definitionName + " " +
                widget.configJson + " " +
                widget.unit
        ).lowercase()

        return listOf(
            "датчик", "сенсор", "sensor",
            "температур", "влажност", "давлен",
            "показани", "измерени", "измерение",
            "статус", "состояни", "индикатор",
            "концевик", "концевой", "градус",
            "humidity", "pressure",
            "anydata", "chart", "gauge", "progress"
        ).any { text.contains(it) } ||
            widget.unit.contains("°") ||
            widget.unit.contains("%") ||
            widget.unit.contains("pa", ignoreCase = true)
    }

    private fun isModeWidget(widget: WidgetState): Boolean {
        val text = EmojiSemanticText.normalize(
            widget.id + " " + widget.title + " " + widget.definitionName
        ).lowercase()

        return listOf("режим", "автомат", "ручн", "управлен", "mode")
            .any { text.contains(it) }
    }

    private fun manualModeValue(
        widget: WidgetState,
        requiredGateValue: String
    ): String {
        val text = EmojiSemanticText.normalize(
            widget.title + " " + widget.definitionName
        ).lowercase()

        if (text.contains("ручн")) return "1"
        if (text.contains("автомат")) return "1"

        return if (requiredGateValue == "0") "1" else "0"
    }

    private fun toCandidate(
        node: Node,
        score: Int
    ): MarfaAnalyticalEngine.ControlCandidate =
        MarfaAnalyticalEngine.ControlCandidate(
            device = node.device,
            widget = node.widget,
            score = score,
            reasons = listOf("scenario graph controller")
        )

    private fun normalizeValue(value: String): String =
        value.trim().replace(',', '.').removeSuffix(".0")

    private fun valuesEquivalent(left: String, right: String): Boolean =
        normalizeValue(left) == normalizeValue(right)
