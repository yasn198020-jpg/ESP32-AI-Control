package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState

/**
 * Scenario-first command resolution.
 *
 * The natural-language resolver only supplies possible candidates. This layer
 * treats the scenario as a directed graph and decides which element is the
 * actual controller:
 *
 *   condition/control -> affected element
 *
 * For a requested element we walk backwards to the highest controllable
 * controller, while keeping mode/gate elements in the separate "blocker"
 * direction. This prevents physical relays, feedback widgets and sensors from
 * becoming voice targets merely because their title matches the command.
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

    fun resolve(
        candidates: List<MarfaAnalyticalEngine.ControlCandidate>,
        desiredValue: String,
        devices: List<Device>,
        models: List<Pair<StoredDeviceScenario, DeviceScenarioModel>>
    ): ScenarioGraphCommandResolution {
        if (candidates.isEmpty() || models.isEmpty()) return ScenarioGraphCommandResolution()

        val nodes = uniqueNodes(devices)
        if (nodes.isEmpty()) return ScenarioGraphCommandResolution()

        val enabledModels = models.filter { it.first.enabled && it.second.valid }
        if (enabledModels.isEmpty()) return ScenarioGraphCommandResolution()

        // target -> elements that occur in the target's scenario conditions.
        // A scenario condition is not automatically a controller relationship.
        // We first identify action targets, then validate each candidate as a
        // control only when a rule explicitly writes that candidate or the
        // candidate is a direct semantic target whose state is represented by
        // the rule. This prevents sensors/feedback/parallel controls from
        // becoming parents merely because they appear in the same condition.
        val parents = linkedMapOf<String, MutableSet<String>>()
        val rulesByTarget = linkedMapOf<String, MutableList<DeviceScenarioRule>>()

        enabledModels.forEach { (_, model) ->
            model.rules.forEach { rule ->
                rule.actions.forEach { action ->
                    rulesByTarget.getOrPut(action.targetId) { mutableListOf() } += rule
                    rule.condition.identifiers.forEach { identifier ->
                    if (identifier != action.targetId) {
                        parents.getOrPut(action.targetId) { linkedSetOf() } += identifier
                    }
                }
                }
            }
        }

        data class Path(
            val candidate: MarfaAnalyticalEngine.ControlCandidate,
            val top: Node,
            val chain: List<String>
        )

        // When the lexical stage found a widget that directly names the
        // requested object, unrelated scenario nodes from the same page must
        // not compete with it. They may still appear as blockers later.
        val graphCandidates = candidates.filter { it.directSemanticTarget }
            .ifEmpty { candidates }

        val paths = graphCandidates.mapNotNull { candidate ->
            if (nodes[candidate.widget.id] == null) return@mapNotNull null
            if (!isScenarioNode(candidate.widget.id, enabledModels)) return@mapNotNull null

            val visited = linkedSetOf<String>()
            val chain = mutableListOf(candidate.widget.id)
            var current = candidate.widget.id
            var ambiguous = false

            // A widget that directly names the requested object is already a
            // proven voice target. Conditions attached to its scenario rules
            // are blockers/prerequisites, not alternative controllers.
            // Only an indirect/physical candidate is allowed to climb the
            // reverse dependency graph looking for its logical controller.
            if (candidate.directSemanticTarget) {
                val top = nodes[current] ?: return@mapNotNull null
                val prerequisites = findBlockers(
                    target = top,
                    chain = chain,
                    desiredValue = desiredValue,
                    parents = parents,
                    rulesByTarget = rulesByTarget,
                    nodes = nodes
                )
                if (prerequisites.blockedReason != null) {
                    return@mapNotNull Path(candidate, top, chain)
                }
                return@mapNotNull Path(candidate, top, chain)
            }

            while (visited.add(current)) {
                val controllerParents = parents[current]
                    .orEmpty()
                    .mapNotNull { nodes[it] }
                    .filter { parent ->
                        isControllable(parent.widget) &&
                            !isModeWidget(parent.widget) &&
                            canActuallyControl(
                                controller = parent.widget,
                                affectedId = current,
                                rulesByTarget = rulesByTarget,
                                nodes = nodes
                            )
                    }
                    .distinctBy { it.widget.id }

                if (controllerParents.size > 1) {
                    // Several independent controls can change the same state.
                    // Do not guess which one the user meant.
                    ambiguous = true
                    break
                }
                val next = controllerParents.singleOrNull() ?: break
                current = next.widget.id
                chain += current
            }

            if (ambiguous) null
            else Path(candidate, nodes[current]!!, chain)
        }

        if (paths.isEmpty()) return ScenarioGraphCommandResolution()

        // A mode/management widget is a gate, not a voice target when the
        // same graph contains an actual object controller.
        val nonModePaths = paths.filterNot { isModeWidget(it.top.widget) }
        val effectivePaths = if (nonModePaths.isNotEmpty()) nonModePaths else paths

        val grouped = effectivePaths.groupBy { it.top.device.id + "/" + it.top.widget.id }
        if (grouped.size > 1) {
            val labels = grouped.values
                .mapNotNull { it.firstOrNull()?.top?.widget?.title?.ifBlank { it.first().top.widget.id } }
                .distinct()
            return ScenarioGraphCommandResolution(
                blockedReason = "Сценарий оставляет несколько независимых управляющих элементов: " +
                    labels.joinToString(" или ") + ".",
                resolvedByScenario = true,
                alternatives = grouped.values.mapNotNull { it.firstOrNull()?.let { p ->
                    toCandidate(p.top, p.candidate.score)
                } }
            )
        }

        val path = grouped.values.first().maxByOrNull { it.candidate.score } ?: return ScenarioGraphCommandResolution()
        val top = path.top

        val prerequisites = findBlockers(
            target = top,
            chain = path.chain,
            desiredValue = desiredValue,
            parents = parents,
            rulesByTarget = rulesByTarget,
            nodes = nodes
        )

        if (prerequisites.blockedReason != null) {
            return ScenarioGraphCommandResolution(
                target = toCandidate(top, path.candidate.score),
                blockedReason = prerequisites.blockedReason,
                resolvedByScenario = true
            )
        }

        return ScenarioGraphCommandResolution(
            target = toCandidate(top, path.candidate.score),
            prerequisites = prerequisites.items,
            resolvedByScenario = true
        )
    }

    private data class BlockerResult(
        val items: List<ScenarioPrerequisite>,
        val blockedReason: String? = null
    )

    /**
     * A node mentioned in a condition can be a sensor, feedback, or an
     * unrelated gate. It is a controller only when the scenario contains a
     * writable action path whose target is the affected state and the
     * candidate's value participates in that rule as the controlling input.
     */
    private fun canActuallyControl(
        controller: WidgetState,
        affectedId: String,
        rulesByTarget: Map<String, List<DeviceScenarioRule>>,
        nodes: Map<String, Node>
    ): Boolean {
        if (nodes[controller.id] == null) return false
        return rulesByTarget[affectedId].orEmpty().any { rule ->
            rule.condition.identifiers.contains(controller.id) &&
                rule.actions.any { it.targetId == affectedId } &&
                isControllable(controller) &&
                !isModeWidget(controller)
        }
    }

    private fun findBlockers(
        target: Node,
        chain: List<String>,
        desiredValue: String,
        parents: Map<String, Set<String>>,
        rulesByTarget: Map<String, List<DeviceScenarioRule>>,
        nodes: Map<String, Node>
    ): BlockerResult {
        val blockers = linkedMapOf<String, MutableSet<String>>()

        /*
         * Every node on the controller -> target path is allowed to depend on
         * additional conditions. Those conditions are gates, not controllers.
         * We collect only writable elements; sensors remain observations.
         */
        chain.forEach { affectedId ->
            rulesByTarget[affectedId].orEmpty().forEach { rule ->
                // OR branches describe alternatives. They must not be turned
                // into simultaneous prerequisites by a simple equality scan;
                // the existing scenario planner remains authoritative for
                // those branches.
                if (containsOperator(rule.condition.expression, "|")) return@forEach

                val controllerIds = parents[affectedId].orEmpty()
                    .filter { it in chain }
                    .toSet()

                rule.condition.identifiers
                    .filter { it !in controllerIds && it != affectedId }
                    .forEach { id ->
                        val node = nodes[id] ?: return@forEach
                        if (!isControllable(node.widget)) return@forEach
                        blockers.getOrPut(id) { linkedSetOf() }.addAll(
                            equalityRequirements(rule.condition.expression, id)
                        )
                    }
            }
        }

        if (blockers.isEmpty()) return BlockerResult(emptyList())

        val result = mutableListOf<ScenarioPrerequisite>()
        blockers.forEach { (id, requiredValues) ->
            val node = nodes[id] ?: return@forEach
            if (requiredValues.isEmpty()) return@forEach

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
                /*
                 * A mode gate is a blocker, not the controller. For a gate
                 * expressed as MODE == 0/1, the voice path must enter the
                 * opposite/manual branch when the widget explicitly exposes
                 * manual control. This is the generic mode rule; the ID and
                 * device are never hard-coded.
                 */
                val manualValue = manualModeValue(node.widget, required)
                if (!valuesEquivalent(actual, manualValue)) {
                    result += ScenarioPrerequisite(
                        deviceId = node.device.id,
                        widgetId = id,
                        value = manualValue,
                        reason = "Блокирует команда: " +
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

        return BlockerResult(result.distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value })
    }

    private fun containsOperator(expression: IoTExpr, wanted: String): Boolean {
        return when (expression) {
            is IoTExpr.Binary ->
                expression.operator == wanted ||
                    containsOperator(expression.left, wanted) ||
                    containsOperator(expression.right, wanted)
            is IoTExpr.Unary -> containsOperator(expression.expression, wanted)
            is IoTExpr.Call -> expression.args.any { containsOperator(it, wanted) }
            is IoTExpr.Variable, is IoTExpr.NumberLiteral, is IoTExpr.StringLiteral -> false
        }
    }

    private fun equalityRequirements(expression: IoTExpr, wanted: String): List<String> {
        val result = mutableListOf<String>()
        fun visit(e: IoTExpr) {
            when (e) {
                is IoTExpr.Binary -> {
                    if (e.operator == "==") {
                        val left = e.left as? IoTExpr.Variable
                        val right = literal(e.right)
                        if (left?.name == wanted && right != null) result += right
                        val rightVar = e.right as? IoTExpr.Variable
                        val leftLiteral = literal(e.left)
                        if (rightVar?.name == wanted && leftLiteral != null) result += leftLiteral
                    }
                    visit(e.left)
                    visit(e.right)
                }
                is IoTExpr.Unary -> visit(e.expression)
                is IoTExpr.Call -> e.args.forEach(::visit)
                is IoTExpr.Variable, is IoTExpr.NumberLiteral, is IoTExpr.StringLiteral -> Unit
            }
        }
        visit(expression)
        return result
    }

    private fun literal(expression: IoTExpr): String? = when (expression) {
        is IoTExpr.NumberLiteral -> expression.value.toString().removeSuffix(".0")
        is IoTExpr.StringLiteral -> expression.value
        else -> null
    }

    private fun uniqueNodes(devices: List<Device>): Map<String, Node> {
        val grouped = devices.flatMap { device ->
            device.widgets.map { widget -> widget.id to Node(device, widget) }
        }.groupBy({ it.first }, { it.second })

        // An element ID is globally authoritative. A duplicate ID is therefore
        // unsafe for scenario graph resolution and is intentionally omitted.
        return grouped.mapNotNull { (id, entries) ->
            if (id.isBlank() || entries.size != 1) null else id to entries.single()
        }.toMap()
    }

    private fun isScenarioNode(
        id: String,
        models: List<Pair<StoredDeviceScenario, DeviceScenarioModel>>
    ): Boolean =
        models.any { (_, model) ->
            model.identifiers.contains(id) || model.rules.any { rule ->
                rule.actions.any { it.targetId == id } ||
                    rule.condition.identifiers.contains(id)
            }
        }

    private fun isControllable(widget: WidgetState): Boolean {
        val text = (widget.id + " " + widget.title + " " + widget.definitionName + " " + widget.unit)
            .lowercase()
        if (listOf(
                "датчик", "сенсор", "sensor", "температур", "влажност", "давлен",
                "показани", "измерени", "измерение", "статус", "состояни",
                "индикатор", "концевик", "концевой", "градус", "humidity", "pressure"
            ).any { text.contains(it) }) return false
        return widget.type == WidgetState.Type.BUTTON ||
            widget.type == WidgetState.Type.TOGGLE ||
            widget.type == WidgetState.Type.INPUT
    }

    private fun isModeWidget(widget: WidgetState): Boolean {
        val text = (widget.id + " " + widget.title + " " + widget.definitionName).lowercase()
        return listOf("режим", "автомат", "ручн", "управлен", "mode").any { text.contains(it) }
    }

    private fun manualModeValue(widget: WidgetState, requiredGateValue: String): String {
        val text = (widget.title + " " + widget.definitionName).lowercase()
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
            reasons = listOf("scenario graph controller"),
        )

    private fun normalizeValue(value: String): String =
        value.trim().replace(',', '.').removeSuffix(".0")

    private fun valuesEquivalent(left: String, right: String): Boolean =
        normalizeValue(left) == normalizeValue(right)
}
