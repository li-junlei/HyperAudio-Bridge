package io.hyperaudio.bridge

import android.Manifest
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val Ink = Color(0xff16171b)
private val Muted = Color(0xff777981)
private val Accent = Color(0xff4545b8)

class MainActivity : ComponentActivity() {
    private var observer: android.database.ContentObserver? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MiuixTheme {
            val computers by BridgeApp.store.computers.collectAsState()
            val nearby by BridgeState.nearby.collectAsState()
            val active by BridgeState.activeId.collectAsState()
            val message by BridgeState.message.collectAsState()
            val root by BridgeState.rootStatus.collectAsState()
            val module by BridgeState.moduleStatus.collectAsState()
            val logging by BridgeState.loggingEnabled.collectAsState()
            var page by remember { mutableIntStateOf(if (intent.getStringExtra("page") == "settings") 2 else 0) }
            var adding by remember { mutableStateOf(false) }
            var pairing by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var deleting by remember { mutableStateOf<String?>(null) }
            var localError by remember { mutableStateOf<String?>(null) }
            val launcher = remember { android.content.ComponentName(this@MainActivity, "$packageName.Launcher") }
            var hideLauncher by remember { mutableStateOf(packageManager.getComponentEnabledSetting(launcher) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
            val scope = rememberCoroutineScope()
            fun pair(text: String) {
                if (busy) return
                busy = true; localError = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Transport.pair(text) }
                        pairing = ""; adding = false; page = 1
                    } catch (e: Exception) { localError = e.message ?: "配对失败" }
                    finally { busy = false }
                }
            }
            val scan = rememberLauncherForActivityResult(ScanContract()) { it.contents?.let(::pair) }
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            val online = computers.count { pc -> nearby.any { it.id == pc.id } }
            val activeName = computers.firstOrNull { it.id == active }?.name
            val stopping = message == "正在停止"
            fun stop() { runCatching { BridgeService.stop(this@MainActivity) }.onFailure { localError = "停止失败，请重试" } }
            Column(Modifier.fillMaxSize().background(Color(0xfff7f7f8)).statusBarsPadding()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.ic_app), null, Modifier.size(42.dp))
                        Text(if (page == 0) "HyperAudio" else if (page == 1) "设备" else "设置", fontSize = 34.sp, fontWeight = FontWeight.Medium, color = Ink)
                    }
                    localError?.let { Panel { Text(it, color = Color(0xffa63e34), fontSize = 14.sp) } }
                    when (page) {
                        0 -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Panel(Modifier.weight(1f).height(220.dp), if (module == "已连接" && root == "已授权") Color(0xffe3f4e6) else Color(0xffeeeaf8)) {
                                    Spacer(Modifier.weight(1f))
                                    Text(if (module == "已连接") "模块已连接" else "模块$module", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = if (module == "已连接") Color(0xff315e3a) else Accent)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Root · $root", fontSize = 15.sp, color = Muted)
                                    Spacer(Modifier.weight(1f))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Panel(Modifier.fillMaxWidth().height(103.dp)) { Caption("运行状态"); Text(if (active != null) "投送中" else if (stopping) "正在停止" else "待机", fontSize = 23.sp, color = Ink) }
                                    Panel(Modifier.fillMaxWidth().height(103.dp)) { Caption("在线设备"); Text("$online / ${computers.size}", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Ink) }
                                }
                            }
                            Panel {
                                Caption("当前设备")
                                Text(activeName ?: Build.MODEL, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
                                Caption(if (active != null) "手机 → 电脑" else "本机播放")
                                if (active != null || stopping) Button(enabled = !stopping, onClick = { stop() }, modifier = Modifier.fillMaxWidth()) { Text(if (stopping) "正在停止" else "停止投送") }
                            }
                            if (active == null && !stopping && message !in listOf("未投送", "本机播放", "选择电脑，开始音频投送")) Panel { Text(message, fontSize = 14.sp, color = Muted) }
                            Panel(Modifier.clickable { page = 1 }) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("已配对设备", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Ink)
                                    Text("${computers.size}  ›", fontSize = 18.sp, color = Muted)
                                }
                                Caption(computers.take(2).joinToString("、") { it.name }.ifEmpty { "暂无设备" })
                            }
                            Button(onClick = { page = 1; adding = true }, modifier = Modifier.fillMaxWidth()) { Text("添加设备") }
                        }
                        1 -> {
                            if (computers.isEmpty() && !adding) Panel { Text("暂无已配对设备", color = Muted) }
                            computers.forEach { pc ->
                                val current = active == pc.id
                                Panel {
                                    Text(pc.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Ink)
                                    Caption(if (current) "投送中" else if (nearby.any { it.id == pc.id }) "在线" else "离线")
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(enabled = !stopping && (active == null || current), onClick = {
                                            if (current) stop() else runCatching { BridgeService.start(this@MainActivity, pc.id) }.onFailure { localError = "连接失败" }
                                        }) { Text(if (current) "停止投送" else "投送声音") }
                                        Button(enabled = !stopping, onClick = { deleting = if (deleting == pc.id) null else pc.id }) { Text("删除") }
                                    }
                                    if (deleting == pc.id) {
                                        Caption("删除后需重新配对")
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button(onClick = {
                                                scope.launch {
                                                    if (current) { stop(); delay(500) }
                                                    BridgeApp.store.forget(pc.id); deleting = null
                                                }
                                            }) { Text("确认删除") }
                                            Button(onClick = { deleting = null }) { Text("取消") }
                                        }
                                    }
                                }
                            }
                            Button(onClick = { adding = !adding }, modifier = Modifier.fillMaxWidth()) { Text(if (adding) "收起添加" else "添加设备") }
                            if (adding) Panel {
                                Text("添加电脑", fontSize = 21.sp, fontWeight = FontWeight.Medium, color = Ink)
                                Caption("扫描 Windows 端配对码")
                                Button(enabled = !busy, onClick = { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描配对码").setBeepEnabled(false).setOrientationLocked(false)) }) { Text("扫码配对") }
                                TextField(value = pairing, onValueChange = { pairing = it }, label = "配对地址", modifier = Modifier.fillMaxWidth())
                                Button(enabled = !busy && pairing.isNotBlank(), onClick = { pair(pairing) }) { Text(if (busy) "正在连接" else "配对") }
                            }
                        }
                        2 -> {
                            Panel {
                                Text("权限与模块", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
                                StatusLine("系统模块", module); StatusLine("Root", root)
                                Caption("LSPosed：系统框架、系统界面、界面插件")
                                Button(onClick = { contentResolver.call(BridgeIpc.URI, "inspect", null, null) }) { Text("重新检测") }
                            }
                            Panel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("诊断日志", fontSize = 19.sp, color = Ink); Caption("不记录音频与配对凭证") }
                                    Switch(checked = logging, onCheckedChange = { enabled -> contentResolver.call(BridgeIpc.URI, "setLogging", null, Bundle().apply { putBoolean("enabled", enabled) }) })
                                }
                            }
                            Panel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("隐藏桌面图标", fontSize = 19.sp, color = Ink); Caption("可从控制中心“更多设置”或 LSPosed 打开") }
                                    Switch(checked = hideLauncher, onCheckedChange = { hidden ->
                                        runCatching {
                                            packageManager.setComponentEnabledSetting(launcher,
                                                if (hidden) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                                android.content.pm.PackageManager.DONT_KILL_APP)
                                            hideLauncher = hidden
                                        }.onFailure { localError = "图标设置失败" }
                                    })
                                }
                            }
                            Panel { StatusLine("音频", "Opus · 48 kHz"); StatusLine("连接", "局域网 · TLS"); StatusLine("版本", "0.5.3") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Row(Modifier.fillMaxWidth().background(Color(0xfff7f7f8)).navigationBarsPadding().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("首页", "设备", "设置").forEachIndexed { index, title ->
                        Column(Modifier.width(90.dp).clickable { page = index }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Image(painterResource(if (index == 0) R.drawable.ic_home else if (index == 1) R.drawable.ic_devices else R.drawable.ic_settings), null, Modifier.size(25.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (page == index) Accent else Muted))
                            Text(title, fontSize = 13.sp, color = if (page == index) Accent else Muted, fontWeight = if (page == index) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        } }
    }
    override fun onStart() {
        super.onStart(); observer = BridgeIpc.observe(this)
        contentResolver.call(BridgeIpc.URI, "inspect", null, null)
    }
    override fun onStop() { observer?.let { contentResolver.unregisterContentObserver(it) }; observer = null; super.onStop() }
}

@Composable private fun Panel(modifier: Modifier = Modifier.fillMaxWidth(), color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(color, RoundedCornerShape(28.dp)).padding(21.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
}
@Composable private fun Caption(text: String) { Text(text, fontSize = 14.sp, color = Muted) }
@Composable private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Caption(label); Text(value, fontSize = 14.sp, color = Ink) }
}
