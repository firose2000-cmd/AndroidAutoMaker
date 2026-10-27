package com.automaker.app

import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.automaker.app.databinding.ActivityInstalledAppsBinding
import com.automaker.app.engine.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class InstalledAppActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInstalledAppsBinding
    private var apps: List<InstalledApps.ListedApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstalledAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        binding.toolbar.setNavigationIconTint(ContextCompat.getColor(this, R.color.on_primary))
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.search.doAfterTextChanged { show(it?.toString().orEmpty()) }
        lifecycleScope.launch {
            val listed = withContext(Dispatchers.IO) { InstalledApps.list(this@InstalledAppActivity) }
            apps = listed
            binding.loading.visibility = View.GONE
            show("")
        }
    }

    private fun show(query: String) {
        val needle = query.trim().lowercase(Locale.US)
        val visible = if (needle.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase(Locale.US).contains(needle) || it.packageName.lowercase(Locale.US).contains(needle)
            }
        }
        binding.empty.visibility = if (visible.isEmpty() && binding.loading.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        binding.apps.adapter = object : ArrayAdapter<InstalledApps.ListedApp>(this, android.R.layout.simple_list_item_2, android.R.id.text1, visible) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val app = getItem(position) ?: return view
                view.findViewById<TextView>(android.R.id.text1).text = app.label
                val detail = if (app.splitCount > 1) {
                    "${app.packageName} · ${app.splitCount} APKs"
                } else {
                    app.packageName
                }
                view.findViewById<TextView>(android.R.id.text2).text = detail
                return view
            }
        }
        binding.apps.setOnItemClickListener { _, _, position, _ ->
            val app = binding.apps.adapter.getItem(position) as? InstalledApps.ListedApp ?: return@setOnItemClickListener
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PACKAGE, app.packageName)
                    .putExtra(EXTRA_LABEL, app.label)
                    .putExtra(EXTRA_SIZE, app.size),
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "packageName"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SIZE = "size"
    }
}
