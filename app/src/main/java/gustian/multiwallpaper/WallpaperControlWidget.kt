package gustian.multiwallpaper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import gustian.multiwallpaper.R

class WallpaperControlWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "gustian.multiwallpaper.TOGGLE_ACTION") {
            val homePrefs = context.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            val lockPrefs = context.getSharedPreferences("multi_wallpaper_prefs_lock", Context.MODE_PRIVATE)
            
            // Get current state from home prefs (we assume they should be synced)
            val currentState = homePrefs.getBoolean("service_enabled", true)
            val newState = !currentState
            
            // Apply to both
            homePrefs.edit().putBoolean("service_enabled", newState).apply()
            lockPrefs.edit().putBoolean("service_enabled", newState).apply()
            
            Log.d("MW_WIDGET", "Global Toggle: service_enabled = $newState")
            
            // Force update all widgets
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WallpaperControlWidget::class.java))
            for (id in ids) {
                updateAppWidget(context, manager, id)
            }
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("service_enabled", true)
        
        val views = RemoteViews(context.packageName, R.layout.wallpaper_control_widget)
        
        // Update icon color based on state
        // Aktif = Gray, Mati = Red
        val iconColor = if (isEnabled) 0xFFBBBBBB.toInt() else 0xFFF44336.toInt()
        views.setInt(R.id.widget_icon, "setColorFilter", iconColor)
        views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_main)
        
        // Setup click intent
        val intent = Intent(context, WallpaperControlWidget::class.java).apply {
            action = "gustian.multiwallpaper.TOGGLE_ACTION"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
