package com.example.widgetlauncher

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.widget.*
import kotlin.math.roundToInt

const val COLS = 4
const val ROWS = 7

class Item(val id: Int, var x: Int, var y: Int, var w: Int, var h: Int)

class MainActivity : Activity() {
    private lateinit var mgr: AppWidgetManager
    private lateinit var host: AppWidgetHost
    private lateinit var root: FrameLayout
    private lateinit var board: FrameLayout
    private lateinit var dock: LinearLayout
    private lateinit var editBar: LinearLayout
    private var drawer: View? = null
    private val items = mutableListOf<Item>()
    private val views = HashMap<Int, AppWidgetHostView>()
    private var editing: Item? = null
    private var pendingId = -1
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()
    private fun cw() = board.width / COLS
    private fun ch() = board.height / ROWS

    inner class Host(c: Context) : AppWidgetHost(c, 1024) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            HV(context, appWidgetId)
    }

    inner class HV(c: Context, private val wid: Int) : AppWidgetHostView(c) {
        private var dx = 0f
        private var dy = 0f
        private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) { items.find { it.id == wid }?.let { startEdit(it) } }
        })
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { dx = e.rawX; dy = e.rawY }
            gd.onTouchEvent(e)
            return editing?.id == wid
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (editing?.id != wid) { gd.onTouchEvent(e); return true }
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> { translationX = e.rawX - dx; translationY = e.rawY - dy }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tx = translationX; val ty = translationY
                    translationX = 0f; translationY = 0f
                    drop(wid, tx, ty)
                }
            }
            return true
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        mgr = AppWidgetManager.getInstance(this)
        host = Host(this)
        board = FrameLayout(this)
        board.setOnClickListener { if (editing != null) stopEdit() }
        board.setOnLongClickListener { pick(); true }
        board.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) board.post { layoutAll() }
        }
        dock = row(btn("＋ Widget") { pick() }, btn("⊞ Ứng dụng") { openDrawer() }, btn("✨ Sắp xếp") { autoArrange() })
        editBar = row(btn("↔＋") { resize(1, 0) }, btn("↔−") { resize(-1, 0) }, btn("↕＋") { resize(0, 1) },
            btn("↕−") { resize(0, -1) }, btn("🗑") { removeSel() }, btn("✓") { stopEdit() })
        editBar.visibility = View.GONE
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(board, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(px(12), px(8), px(12), px(8)) })
            val m = LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), 0, px(16), px(12)) }
            addView(dock, m); addView(editBar, m)
        }
        root = FrameLayout(this).apply { fitsSystemWindows = true; addView(main, -1, -1) }
        setContentView(root)
        restore()
    }

    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { super.onStop(); host.stopListening() }

    override fun onBackPressed() { if (drawer != null) closeDrawer() else if (editing != null) stopEdit() }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); closeDrawer(); stopEdit() }

    // ---------- UI helpers ----------
    private fun btn(t: String, f: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        setPadding(px(6), px(12), px(6), px(12))
        background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(0x33FFFFFF) }
        setOnClickListener { f() }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER; setPadding(px(8), px(8), px(8), px(8))
        background = GradientDrawable().apply { cornerRadius = px(30).toFloat(); setColor(0xAA14151F.toInt()) }
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(px(3), 0, px(3), 0) }) }
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- Layout ----------
    private fun fits(it: Item?, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (x < 0 || y < 0 || x + w > COLS || y + h > ROWS) return false
        return items.none { o -> o !== it && x < o.x + o.w && x + w > o.x && y < o.y + o.h && y + h > o.y }
    }
    private fun spot(it: Item?, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..ROWS - h) for (x in 0..COLS - w) if (fits(it, x, y, w, h)) return x to y
        return null
    }
    private fun layoutAll() { items.forEach { layout(it) } }
    private fun layout(it: Item) {
        val v = views[it.id] ?: return
        if (board.width == 0) return
        val m = px(4)
        v.layoutParams = FrameLayout.LayoutParams(it.w * cw() - 2 * m, it.h * ch() - 2 * m).apply {
            leftMargin = it.x * cw() + m; topMargin = it.y * ch() + m
        }
        val wd = (it.w * cw() / dp).toInt(); val hd = (it.h * ch() / dp).toInt()
        v.updateAppWidgetSize(Bundle(), wd, hd, wd, hd)
        v.foreground = if (editing === it) GradientDrawable().apply {
            setStroke(px(3), Color.WHITE); cornerRadius = px(20).toFloat()
        } else null
    }
    private fun makeView(id: Int) {
        val info = mgr.getAppWidgetInfo(id) ?: return
        val v = host.createView(this, id, info) as AppWidgetHostView
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, px(20).toFloat()) }
        }
        v.clipToOutline = true
        views[id] = v; board.addView(v)
    }

    // ---------- Edit mode ----------
    private fun startEdit(it: Item) {
        editing = it
        board.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dock.visibility = View.GONE; editBar.visibility = View.VISIBLE
        views[it.id]?.bringToFront(); layoutAll()
    }
    private fun stopEdit() {
        editing = null; dock.visibility = View.VISIBLE; editBar.visibility = View.GONE; layoutAll(); save()
    }
    private fun drop(id: Int, tx: Float, ty: Float) {
        val it = items.find { i -> i.id == id } ?: return
        val nx = (it.x + tx / cw()).roundToInt().coerceIn(0, COLS - it.w)
        val ny = (it.y + ty / ch()).roundToInt().coerceIn(0, ROWS - it.h)
        if (fits(it, nx, ny, it.w, it.h)) { it.x = nx; it.y = ny } else toast("Không đủ chỗ ở vị trí này")
        layout(it); save()
    }
    private fun resize(dw: Int, dh: Int) {
        val it = editing ?: return
        val w = (it.w + dw).coerceIn(1, COLS); val h = (it.h + dh).coerceIn(1, ROWS)
        if (fits(it, it.x, it.y, w, h)) { it.w = w; it.h = h; layout(it); save() } else toast("Không đủ chỗ để đổi cỡ")
    }
    private fun removeSel() {
        val it = editing ?: return
        host.deleteAppWidgetId(it.id); board.removeView(views.remove(it.id)); items.remove(it); stopEdit()
    }
    private fun autoArrange() {
        val old = items.map { Item(it.id, it.x, it.y, it.w, it.h) }
        val placed = mutableListOf<Item>()
        val saved = items.toList()
        items.clear()
        for (o in saved.sortedWith(compareByDescending<Item> { it.w * it.h }.thenByDescending { it.w })) {
            val p = spot(null, o.w, o.h)
            if (p == null) { items.clear(); items.addAll(saved); toast("Không đủ chỗ để sắp xếp"); return }
            o.x = p.first; o.y = p.second; items.add(o)
        }
        layoutAll(); save(); toast("Đã sắp xếp gọn gàng ✨")
    }

    // ---------- Add widget ----------
    private fun pick() {
        val pm = packageManager
        val list = mgr.installedProviders.filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .sortedBy { it.loadLabel(pm).lowercase() }
        val names = list.map { "${it.loadLabel(pm)}  ·  ${it.provider.packageName}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Chọn widget").setItems(names) { _, i -> bind(list[i]) }.show()
    }
    private fun bind(p: AppWidgetProviderInfo) {
        pendingId = host.allocateAppWidgetId()
        if (mgr.bindAppWidgetIdIfAllowed(pendingId, p.provider)) afterBind()
        else startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider), 1)
    }
    private fun afterBind() {
        val p = mgr.getAppWidgetInfo(pendingId)
        if (p?.configure != null) host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, 2, null) else finishAdd()
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(r: Int, res: Int, d: Intent?) {
        if (r != 1 && r != 2) return
        if (res != RESULT_OK) { host.deleteAppWidgetId(pendingId); return }
        if (r == 1) afterBind() else finishAdd()
    }
    private fun finishAdd() {
        val p = mgr.getAppWidgetInfo(pendingId) ?: return
        var w = (((p.minWidth / dp) + 30) / 70).toInt().coerceIn(1, COLS)
        var h = (((p.minHeight / dp) + 30) / 70).toInt().coerceIn(1, ROWS)
        var s = spot(null, w, h)
        while (s == null && (w > 1 || h > 1)) { if (w > 1) w--; if (h > 1) h--; s = spot(null, w, h) }
        if (s == null) { host.deleteAppWidgetId(pendingId); toast("Màn hình đã hết chỗ"); return }
        items.add(Item(pendingId, s.first, s.second, w, h))
        makeView(pendingId); layoutAll(); save()
    }

    // ---------- Persistence ----------
    private fun save() = getSharedPreferences("wl", 0).edit()
        .putString("items", items.joinToString(";") { "${it.id},${it.x},${it.y},${it.w},${it.h}" }).apply()
    private fun restore() {
        getSharedPreferences("wl", 0).getString("items", "")!!.split(";").filter { it.isNotBlank() }.forEach {
            val p = it.split(",").map { n -> n.toInt() }
            if (mgr.getAppWidgetInfo(p[0]) != null) { items.add(Item(p[0], p[1], p[2], p[3], p[4])); makeView(p[0]) }
        }
    }

    // ---------- App drawer ----------
    private fun closeDrawer() { drawer?.let { root.removeView(it) }; drawer = null }
    private fun openDrawer() {
        if (drawer != null) return
        val pm = packageManager
        val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        var shown: List<ResolveInfo> = all
        val grid = GridView(this).apply {
            numColumns = 4; verticalSpacing = px(14); selector = ColorDrawable(0); clipToPadding = false
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                val r = shown[i]
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    addView(ImageView(context).apply { setImageDrawable(r.loadIcon(pm)) }, LinearLayout.LayoutParams(px(52), px(52)))
                    addView(TextView(context).apply {
                        text = r.loadLabel(pm); setTextColor(Color.WHITE); textSize = 12f; maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(px(2), px(6), px(2), 0)
                    })
                }
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, i, _ ->
            val ai = shown[i].activityInfo
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            closeDrawer()
        }
        val search = EditText(this).apply {
            hint = "Tìm ứng dụng…"; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); setSingleLine()
            setPadding(px(18), px(12), px(18), px(12))
            background = GradientDrawable().apply { cornerRadius = px(26).toFloat(); setColor(0x33FFFFFF) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase()
                    shown = all.filter { it.loadLabel(pm).toString().lowercase().contains(q) }
                    adapter.notifyDataSetChanged()
                }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xEE0E0F18.toInt()); setPadding(px(16), px(16), px(16), px(8))
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(16) })
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        drawer = panel; root.addView(panel, -1, -1)
    }
}
