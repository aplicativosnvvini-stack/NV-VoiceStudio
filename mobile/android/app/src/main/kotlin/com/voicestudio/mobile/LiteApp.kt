package com.voicestudio.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicestudio.mobile.designsystem.*
import com.voicestudio.mobile.features.*
import com.voicestudio.mobile.features.models.ModelCard
import com.voicestudio.mobile.l10n.L10n
import com.voicestudio.mobile.local.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*

private const val OMNI = "omnivoice-q4-k-m"
private const val WHISPER = "whisper-tiny-int8"
private const val WHISPER_BASE = "whisper-base-int8"

@Composable
fun LiteApp(incomingIntent: Intent?, onIncomingConsumed: () -> Unit, vm: StudioViewModel = viewModel()) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val phone by vm.phone.state.collectAsState()
    val state by vm.state.collectAsState()
    val prefs by vm.preferences.collectAsState()
    var spanish by rememberSaveable { mutableStateOf(Locale.getDefault().language == "es") }
    val l = remember(spanish) { L10n.load(context, if(spanish) "es" else "pt") }
    fun msg(pt: String, es: String) = if(spanish) es else pt
    var tab by rememberSaveable { mutableStateOf("clone") }
    var name by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var localError by remember { mutableStateOf<String?>(null) }
    val recorder = remember { SampleRecorder(context) }
    val omni = phone.models.firstOrNull { it.id == OMNI }
    val asr = phone.models.firstOrNull { it.id == WHISPER && phone.canRun(it) }
        ?: phone.models.firstOrNull { it.id == WHISPER_BASE && phone.canRun(it) }
        ?: phone.models.firstOrNull { it.id == WHISPER }
    val modelsReady = omni?.let { phone.canRun(it) } == true && asr?.let { phone.canRun(it) } == true
    val busy = phone.progress != null || recording || importing || exporting
    val selected = phone.voices.firstOrNull { it.id == selectedId } ?: phone.voices.firstOrNull()
    val sourceFile = source?.let(::File)?.takeIf { it.isFile }

    fun useSource(file: File) {
        source?.let { if(it != file.absolutePath) File(it).delete() }
        source = file.absolutePath
        consent = false
        vm.clearError()
        localError = null
        tab = "clone"
    }
    fun stopRecord() {
        try { useSource(recorder.stop()) }
        catch(e: Exception) { vm.restoreFailure(e) }
        finally { recording = false }
    }
    fun startRecord() {
        try { recorder.start(); recording = true }
        catch(e: Exception) { vm.restoreFailure(e) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && !busy) startRecord()
        else if(!granted) localError = msg("Permissão de microfone necessária.", "Se necesita permiso de micrófono.")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null && !busy) {
            importing = true
            scope.launch {
                try { useSource(copyMedia(context, uri, "audio", 64L*1024*1024)) }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { vm.restoreFailure(e) }
                finally { importing = false }
            }
        }
    }
    LaunchedEffect(incomingIntent, busy) {
        if(incomingIntent != null && !busy) {
            try {
                val incoming = PhoneIncoming.read(context, incomingIntent)
                if(incoming?.file != null && incomingIntent.type.orEmpty().startsWith("audio/")) useSource(incoming.file!!)
                else if(incoming?.text != null) { text = incoming.text!!; tab = "speak" }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { vm.restoreFailure(e) }
            finally { onIncomingConsumed() }
        }
    }
    LaunchedEffect(recording) {
        if(recording) { repeat(29) { delay(1000) }; if(recording) stopRecord() }
    }
    LaunchedEffect(phone.progress != null) {
        elapsed = 0
        while(phone.progress != null) { delay(1000); elapsed++ }
    }
    DisposableEffect(view, phone.progress != null || recording) {
        view.keepScreenOn = phone.progress != null || recording
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { recorder.discard() } }

    CompositionLocalProvider(LocalL10n provides l) {
        VoiceStudioTheme(ThemePreferences(prefs.mode,prefs.lightPalette,prefs.darkPalette)) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        listOf("clone" to msg("Clonar","Clonar"), "speak" to msg("Gerar áudio","Generar audio"), "models" to msg("Modelos","Modelos")).forEach { (id,label) ->
                            NavigationBarItem(selected=tab==id, onClick={tab=id}, enabled=!recording && phone.progress==null && !importing, icon={Text(when(id) {"clone"->"1";"speak"->"2";else->"3"})}, label={Text(label)})
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("NV Voice Lite",style=MaterialTheme.typography.titleLarge)
                        TextButton(onClick={spanish=!spanish}) { Text(if(spanish) "ES / PT" else "PT / ES") }
                    }
                    Text(msg("Clonagem de voz no celular • Offline","Clonación de voz en el teléfono • Sin conexión"))
                    localError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    state.error?.let { error ->
                        ErrorMessage(error.key,error.params)
                        TextButton(onClick={vm.clearError()}) { Text(msg("Fechar","Cerrar")) }
                    }
                    phone.progress?.let { p ->
                        val stage = if(tab=="clone" && p<.25f) msg("Preparando áudio","Preparando audio")
                            else if(tab=="clone" && p<.5f) msg("Transcrevendo amostra","Transcribiendo muestra")
                            else msg("Processando a voz","Procesando la voz")
                        Text("$stage • ${elapsed/60}m ${elapsed%60}s")
                        if((tab=="clone" && p>=.5f)||(tab=="speak")) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress={p},modifier=Modifier.fillMaxWidth())
                        OutlinedButton(onClick={vm.phone.cancelInference()}) { Text(msg("Cancelar","Cancelar")) }
                    }
                    when(tab) {
                        "clone" -> {
                            Text(msg("Nova voz","Nueva voz"),style=MaterialTheme.typography.titleMedium)
                            OutlinedTextField(value=name,onValueChange={name=it.take(200)},label={Text(msg("Nome da voz","Nombre de la voz"))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true)
                            OutlinedButton(onClick={picker.launch(arrayOf("audio/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(msg("Selecionar áudio / WhatsApp","Seleccionar audio / WhatsApp")) }
                            if(recording) OutlinedButton(onClick={stopRecord()}) { Text(msg("Parar gravação","Detener grabación")) }
                            else OutlinedButton(enabled=!busy,onClick={
                                if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startRecord()
                                else permission.launch(Manifest.permission.RECORD_AUDIO)
                            }) { Text(msg("Gravar voz","Grabar voz")) }
                            sourceFile?.let { file ->
                                Text(msg("Amostra selecionada","Muestra seleccionada"))
                                if(!busy) LocalAudioPlayer(file,vm::failureHandler)
                                TextButton(enabled=!busy,onClick={file.delete();source=null;consent=false}) { Text(msg("Remover amostra","Quitar muestra")) }
                            }
                            ConsentCheck("mobile.simple.voice_consent",consent) { consent=it; if(!it) vm.phone.cancelInference() }
                            Info("mobile.voices.consent_title","mobile.voices.consent_body")
                            if(!modelsReady) OutlinedButton(onClick={tab="models"},enabled=!busy) { Text(msg("Instalar modelos necessários","Instalar modelos necesarios")) }
                            Button(enabled=!busy && consent && name.isNotBlank() && sourceFile!=null && modelsReady,onClick={
                                vm.phone.clone(name,sourceFile!!,asr!!,omni!!,consent) {source=null;name="";consent=false}
                            },modifier=Modifier.fillMaxWidth()) { Text(msg("Clonar e salvar","Clonar y guardar")) }
                            Text(msg("Minhas vozes","Mis voces"),style=MaterialTheme.typography.titleMedium)
                            phone.voices.forEach { voice ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(voice.name,style=MaterialTheme.typography.titleMedium)
                                        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                            TextButton(enabled=!busy,onClick={selectedId=voice.id;tab="speak"}) {Text(msg("Usar","Usar"))}
                                            TextButton(enabled=!busy,onClick={previewId=if(previewId==voice.id) null else voice.id}) {Text(msg("Ouvir","Escuchar"))}
                                            TextButton(enabled=!busy,onClick={deleteId=voice.id}) {Text(msg("Excluir","Eliminar"))}
                                        }
                                        if(previewId==voice.id && !busy) LocalAudioPlayer(vm.phone.voices.preview(voice),vm::failureHandler)
                                    }
                                }
                            }
                        }
                        "speak" -> {
                            Text(msg("Gerar fala com voz clonada","Generar habla con voz clonada"),style=MaterialTheme.typography.titleMedium)
                            if(selected==null) TextButton(onClick={tab="clone"}) { Text(msg("Clone uma voz primeiro","Clona una voz primero")) }
                            else {
                                ChoicePicker("mobile.speak.voice",selected.id,phone.voices.map { Choice(it.id,it.name) }) {selectedId=it}
                                OutlinedTextField(text,{text=it.take(2000)},label={Text(msg("Texto em português ou espanhol","Texto en portugués o español"))},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=140.dp))
                                Text(msg("O idioma é detectado pelo texto.","El idioma se detecta por el texto."))
                                Button(enabled=!busy && text.isNotBlank() && omni?.let {phone.canRun(it)}==true,onClick={vm.phone.speak(omni!!,text,0,selected)},modifier=Modifier.fillMaxWidth()) {Text(msg("Gerar áudio","Generar audio"))}
                            }
                            phone.audio?.let { file ->
                                if(!busy) LocalAudioPlayer(file,vm::failureHandler)
                                OutlinedButton(enabled=!busy,onClick={
                                    exporting=true
                                    scope.launch {
                                        try { val mp3=BookExport.encode(file,"mp3");shareLocalFile(context,mp3,"audio/mpeg") }
                                        catch(e: CancellationException) {throw e}
                                        catch(e: Exception) {vm.restoreFailure(e)}
                                        finally {exporting=false}
                                    }
                                },modifier=Modifier.fillMaxWidth()) {Text(msg("Compartilhar MP3","Compartir MP3"))}
                            }
                            Text(msg("Histórico","Historial"),style=MaterialTheme.typography.titleMedium)
                            phone.recent.forEach { item ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(item.text.take(90),Modifier.weight(1f))
                                    TextButton(enabled=!busy,onClick={vm.phone.play(item)}) {Text(msg("Ouvir","Escuchar"))}
                                    TextButton(enabled=!busy,onClick={vm.phone.deleteGeneration(item)}) {Text(msg("Excluir","Eliminar"))}
                                }
                            }
                        }
                        else -> {
                            Text(msg("Baixe uma vez e use offline.","Descarga una vez y usa sin conexión."))
                            if(phone.loading) CircularProgressIndicator()
                            Toggle("mobile.simple.mobile_data",prefs.allowMobileModels) {allowed -> vm.preference {it.copy(allowMobileModels=allowed)}}
                            listOfNotNull(omni,asr).forEach {ModelCard(vm,it)}
                            Text(msg("OmniVoice: licença de uso não comercial. Clone somente vozes autorizadas.","OmniVoice: licencia de uso no comercial. Clona solo voces autorizadas."))
                        }
                    }
                }
            }
            deleteId?.let { id ->
                AlertDialog(onDismissRequest={deleteId=null},title={Text(msg("Excluir voz?","¿Eliminar voz?"))},
                    confirmButton={TextButton(onClick={phone.voices.firstOrNull {it.id==id}?.let(vm.phone::deleteVoice);deleteId=null;previewId=null}) {Text(msg("Excluir","Eliminar"))}},
                    dismissButton={TextButton(onClick={deleteId=null}) {Text(msg("Cancelar","Cancelar"))}})
            }
        }
    }
}
