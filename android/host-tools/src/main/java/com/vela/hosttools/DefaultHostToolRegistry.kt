package com.vela.hosttools

import com.vela.core.domain.HostTool
import com.vela.core.domain.HostToolRegistry

/** Simple in-memory implementation of [HostToolRegistry] over a fixed tool list. */
class DefaultHostToolRegistry(private val tools: List<HostTool>) : HostToolRegistry {
    private val byName = tools.associateBy { it.name }

    override fun all(): List<HostTool> = tools

    override fun find(name: String): HostTool? = byName[name]
}
