package com.zeyos.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zeyos.app.R
import com.zeyos.app.ui.adapter.LogAdapter
import com.zeyos.app.util.Logger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LogViewerActivity : AppCompatActivity() {

    private val adapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        val recyclerView = findViewById<RecyclerView>(R.id.rvLogs)
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.btnShareLog).setOnClickListener { shareLogFile() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { Logger.clearMemoryBuffer() }

        lifecycleScope.launch {
            Logger.entries.collect { entries ->
                adapter.submitList(entries)
                if (entries.isNotEmpty()) recyclerView.scrollToPosition(entries.size - 1)
            }
        }
    }

    private fun shareLogFile() {
        val file = Logger.currentLogFile() ?: return
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Zey OS log"))
    }
}
