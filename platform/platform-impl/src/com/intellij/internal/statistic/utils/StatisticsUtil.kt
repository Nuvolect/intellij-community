// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.internal.statistic.utils

import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerMain
import com.intellij.internal.statistic.beans.UsageDescriptor
import com.intellij.internal.statistic.service.fus.collectors.FUSUsageContext
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.ObjectIntHashMap
import gnu.trove.THashSet
import java.util.*

fun getProjectId(project: Project): String {
  return project.getProjectCacheFileName(false, ".").hashCode().toString()
}

fun createData(project: Project?, context: FUSUsageContext?): Map<String, Any> {
  if (project == null && context == null) return Collections.emptyMap()

  val data = ContainerUtil.newHashMap<String, Any>()
  if (context != null) {
    data.putAll(context.data)
  }

  if (project != null) {
    data["project"] = getProjectId(project)
  }
  return data
}

fun mergeWithEventData(data: Map<String, Any>, context: FUSUsageContext?, value : Int): Map<String, Any> {
  if (context == null && value == 1) return data

  val newData = ContainerUtil.newHashMap<String, Any>()
  newData.putAll(data)

  if (value != 1) {
    newData["value"] = value
  }

  context?.let {
    for (datum in it.data) {
      newData["event_" + datum.key] = datum.value
    }
  }
  return newData
}

fun isDevelopedByJetBrains(pluginId: PluginId?): Boolean {
  val plugin = PluginManager.getPlugin(pluginId)
  return plugin == null || plugin.isBundled || PluginManagerMain.isDevelopedByJetBrains(plugin.vendor)
}

/**
 * Constructs a proper UsageDescriptor for a boolean value,
 * by adding "enabled" or "disabled" suffix to the given key, depending on the value.
 */
fun getBooleanUsage(key: String, value: Boolean): UsageDescriptor {
  return UsageDescriptor(key + if (value) ".enabled" else ".disabled", 1)
}

fun getEnumUsage(key: String, value: Enum<*>?): UsageDescriptor {
  return UsageDescriptor(key + "." + value?.name?.toLowerCase(Locale.ENGLISH), 1)
}

/**
 * Constructs a proper UsageDescriptor for a counting value.
 * If one needs to know a number of some items in the project, there is no direct way to report usages per-project.
 * Therefore this workaround: create several keys representing interesting ranges, and report that key which correspond to the range
 * which the given value belongs to.
 *
 * For example, to report a number of commits in Git repository, you can call this method like that:
 * ```
 * val usageDescriptor = getCountingUsage("git.commit.count", listOf(0, 1, 100, 10000, 100000), realCommitCount)
 * ```
 * and if there are e.g. 50000 commits in the repository, one usage of the following key will be reported: `git.commit.count.10K+`.
 *
 * NB:
 * (1) the list of steps must be sorted ascendingly; If it is not, the result is undefined.
 * (2) the value should lay somewhere inside steps ranges. If it is below the first step, the following usage will be reported:
 * `git.commit.count.<1`.
 *
 * @key   The key prefix which will be appended with "." and range code.
 * @steps Limits of the ranges. Each value represents the start of the next range. The list must be sorted ascendingly.
 * @value Value to be checked among the given ranges.
 */
fun getCountingUsage(key: String, value: Int, steps: List<Int>) : UsageDescriptor {
  if (steps.isEmpty()) return UsageDescriptor("$key.$value", 1)
  if (value < steps[0]) return UsageDescriptor("$key.<${steps[0]}", 1)

  var stepIndex = 0
  while (stepIndex < steps.size - 1) {
    if (value < steps[stepIndex + 1]) break
    stepIndex++
  }

  val step = steps[stepIndex]
  val addPlus = stepIndex == steps.size - 1 || steps[stepIndex + 1] != step + 1
  val stepName = humanize(step) + if (addPlus) "+" else ""
  return UsageDescriptor("$key.$stepName", 1)
}

/**
 * [getCountingUsage] with steps (0, 1, 2, 3, 5, 10, 15, 30, 50, 100, 500, 1000, 5000, 10000, ...)
 */
fun getCountingUsage(key: String, value: Int): UsageDescriptor {
  if (value > Int.MAX_VALUE / 10) return UsageDescriptor("$key.MANY", 1)
  if (value < 0) return UsageDescriptor("$key.<0", 1)
  if (value < 3) return UsageDescriptor("$key.$value", 1)

  val fixedSteps = listOf(3, 5, 10, 15, 30, 50)

  var step = fixedSteps.last { it <= value }
  while (true) {
    if (value < step * 2) break
    step *= 2
    if (value < step * 5) break
    step *= 5
  }

  val stepName = humanize(step)
  return UsageDescriptor("$key.$stepName+", 1)
}

private const val kilo = 1000
private val mega = kilo * kilo

private fun humanize(number: Int): String {
  if (number == 0) return "0"
  val m = number / mega
  val k = (number % mega) / kilo
  val r = (number % kilo)
  val ms = if (m > 0) "${m}M" else ""
  val ks = if (k > 0) "${k}K" else ""
  val rs = if (r > 0) "${r}" else ""
  return ms + ks + rs
}

fun toUsageDescriptors(result: ObjectIntHashMap<String>): Set<UsageDescriptor> {
  if (result.isEmpty) {
    return emptySet()
  }
  else {
    val descriptors = THashSet<UsageDescriptor>(result.size())
    result.forEachEntry { key, value ->
      descriptors.add(UsageDescriptor(key, value))
      true
    }
    return descriptors
  }
}

fun merge(first: Set<UsageDescriptor>, second: Set<UsageDescriptor>): Set<UsageDescriptor> {
  if (first.isEmpty()) {
    return second
  }

  if (second.isEmpty()) {
    return first
  }

  val merged = ObjectIntHashMap<String>()
  addAll(merged, first)
  addAll(merged, second)
  return toUsageDescriptors(merged)
}

private fun addAll(result: ObjectIntHashMap<String>, usages: Set<UsageDescriptor>) {
  for (usage in usages) {
    val key = usage.key
    result.put(key, result.get(key, 0) + usage.value)
  }
}
