package com.mitaxer.logger.sample

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mitaxer.logger.XLog

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 Native 日志
        XLog.init(this, "DEMO")

        // 简单 UI
        val scrollView = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
        }
        scrollView.addView(logView)

        val btn1 = Button(this).apply { text = "写 100 条日志" }
        val btn2 = Button(this).apply { text = "写 5000 条日志" }
        val btn3 = Button(this).apply { text = "强制刷盘" }
        val btn5 = Button(this).apply { text = "模拟崩溃" }
        val btn4 = Button(this).apply { text = "清屏" }

        btn1.setOnClickListener {
            val t1 = System.nanoTime()
            repeat(100) { i ->
                XLog.i("BENCH", "test log #$i " + "x".repeat(1000))
            }
            val t2 = System.nanoTime()
            XLog.flush()
            appendLog("100 条日志: ${(t2 - t1) / 1000} μs")
        }

        btn2.setOnClickListener {
            val t1 = System.nanoTime()
            repeat(5000) { i ->
                XLog.d("BENCH", "[日志]benchmark log #$i " + "x".repeat(2000))
            }
            val t2 = System.nanoTime()
            XLog.flush()
            appendLog("5000 条日志: ${(t2 - t1) / 1000 / 1000} ms")
        }

        btn3.setOnClickListener {
            XLog.flush()
            appendLog("Flush 完成")
        }

        btn4.setOnClickListener {
            logView.text = ""
        }

        btn5.setOnClickListener {
            repeat(10) { i ->
                XLog.i("CRASH", "崩溃前第${i}条日志 " + "x".repeat(50))
            }
            appendLog("已写入10条崩溃测试日志，即将退出...")
            // 不调 flush，直接退出模拟崩溃
            Thread { Thread.sleep(100); android.os.Process.killProcess(android.os.Process.myPid()) }.start()
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(btn1)
            addView(btn2)
            addView(btn3)
            addView(btn4)
            addView(btn5)
            addView(scrollView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        setContentView(layout)

        appendLog("XLog Demo 启动完成")
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            logView.append("$msg\n")
            android.util.Log.d("MainActivity", msg)
        }
    }
}
