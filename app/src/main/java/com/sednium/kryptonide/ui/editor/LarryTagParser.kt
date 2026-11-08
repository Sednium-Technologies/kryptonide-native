package com.sednium.kryptonide.ui.editor

import com.sednium.kryptonide.agent.oorty.OortyBlock
import com.sednium.kryptonide.agent.oorty.OortyTagParser

sealed class LarryBlock {
    data class Thinking(val text: String) : LarryBlock()
    data class Ans(val text: String) : LarryBlock()
    data class Plan(val text: String) : LarryBlock()
    data class Status(val text: String) : LarryBlock()
    data class Edit(val file: String, val content: String) : LarryBlock()
}

object LarryTagParser {
    fun parse(raw: String): List<LarryBlock> {
        val oortyBlocks = OortyTagParser.parse(raw)
        return oortyBlocks.map { b ->
            when (b) {
                is OortyBlock.Thinking -> LarryBlock.Thinking(b.text)
                is OortyBlock.Ans -> LarryBlock.Ans(b.text)
                is OortyBlock.Plan -> LarryBlock.Plan(b.text)
                is OortyBlock.Status -> LarryBlock.Status(b.text)
                is OortyBlock.Edit -> LarryBlock.Edit(b.file, b.content)
                is OortyBlock.AifNote -> LarryBlock.Status("AIF Memory [Imp: ${b.importance}]: ${b.summary}")
                is OortyBlock.ToolCall -> LarryBlock.Status("Calling tool: ${b.name}")
            }
        }
    }

    fun cleanEditContent(rawContent: String): String = OortyTagParser.cleanEditContent(rawContent)
}
