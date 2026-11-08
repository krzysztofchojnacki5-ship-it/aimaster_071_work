package pl.aimasterpro.app.agents

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object AgentManagerScreen {

    fun buildView(context: Context, onAction: (String, String) -> Unit): View {
        val store = AgentManagerStore(context)
        val summary = store.getSummary()

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val title = TextView(context).apply {
            text = "AI Master — Agent Manager Parity"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val summaryCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
        }
        val summaryText = TextView(context).apply {
            text = "Total Agents: ${summary.totalAgents} | Running: ${summary.runningAgents} | Completed: ${summary.completedAgents} | Failed: ${summary.failedAgents}\nTotal Tokens: ${summary.totalTokensConsumed}"
            textSize = 13f
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        summaryCard.addView(summaryText)
        root.addView(summaryCard)

        val space = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 24)
        }
        root.addView(space)

        summary.activeExecutions.forEach { record ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1A1A"))
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 16) }
            }

            val header = TextView(context).apply {
                text = "${record.agentName} [${record.status}]"
                textSize = 15f
                setTextColor(Color.parseColor("#4CAF50"))
                setTypeface(null, Typeface.BOLD)
            }
            card.addView(header)

            val details = TextView(context).apply {
                text = "ID: ${record.executionId} | Provider: ${record.provider} / ${record.model}\nTokens: ${record.tokensUsed} | Duration: ${record.durationMs}ms\nLog: ${record.lastLog}"
                textSize = 12f
                setTextColor(Color.parseColor("#B0B0B0"))
                setPadding(0, 8, 0, 12)
            }
            card.addView(details)

            if (record.status == AgentExecutionStatus.RUNNING) {
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val stopBtn = Button(context).apply {
                    text = "Stop"
                    textSize = 11f
                    setOnClickListener {
                        store.controlAgent(record.executionId, "stop")
                        onAction(record.executionId, "stop")
                    }
                }
                btnRow.addView(stopBtn)
                card.addView(btnRow)
            }

            root.addView(card)
        }

        scroll.addView(root)
        return scroll
    }
}
