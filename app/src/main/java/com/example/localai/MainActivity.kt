package com.example.localai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("localai")
        }
    }

    external fun loadModel(modelPath: String): String
    external fun generateResponse(prompt: String): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                ModelScreen(
                    loadModel = { loadModel(it) },
                    generateResponse = { generateResponse(it) }
                )
            }
        }
    }
}

@Composable
fun ModelScreen(
    loadModel: (String) -> String,
    generateResponse: (String) -> String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    var status by remember { mutableStateOf("Select a GGUF model to begin.") }
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isBusy = true
            modelLoaded = false
            status = "Copying and loading model..."

            executor.execute {
                try {
                    val modelFile = File(context.filesDir, "selected-model.gguf")

                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open selected file." }
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val result = loadModel(modelFile.absolutePath)

                    (context as ComponentActivity).runOnUiThread {
                        status = result
                        modelLoaded = result.contains("successfully", ignoreCase = true)
                        isBusy = false
                    }
                } catch (e: Exception) {
                    (context as ComponentActivity).runOnUiThread {
                        status = "Error: ${e.message}"
                        isBusy = false
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("LocalAI", style = MaterialTheme.typography.headlineLarge)

        Button(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            enabled = !isBusy
        ) {
            Text("Select GGUF Model")
        }

        Text(status)

        Divider()

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your prompt") },
            placeholder = { Text("Ask something...") }
        )

        Button(
            onClick = {
                if (prompt.isNotBlank() && modelLoaded) {
                    val question = prompt
                    isBusy = true
                    response = "Generating..."

                    executor.execute {
                        val result = try {
                            generateResponse(question)
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }

                        (context as ComponentActivity).runOnUiThread {
                            response = result
                            isBusy = false
                        }
                    }
                }
            },
            enabled = modelLoaded && !isBusy && prompt.isNotBlank()
        ) {
            Text("Generate")
        }

        Text("Response", style = MaterialTheme.typography.titleMedium)

        Text(
            text = response,
            modifier = Modifier.fillMaxWidth()
        )

        if (isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Divider()

        Text("Accessibility", style = MaterialTheme.typography.titleLarge)

        AccessibilityPanel(
            canRunModel = modelLoaded && !isBusy,
            runLlm = { text, onDone ->
                isBusy = true
                executor.execute {
                    val result = try {
                        generateResponse(text)
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    (context as ComponentActivity).runOnUiThread {
                        isBusy = false
                        onDone(result)
                    }
                }
            }
        )
    }
}

@Composable
fun AccessibilityPanel(
    canRunModel: Boolean,
    runLlm: (String, (String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("1. Enable screen reader service")
        }

        Button(
            enabled = canRunModel,
            onClick = {
                val svc = ScreenReaderService.instance
                if (svc == null) {
                    output = "Service not enabled yet."
                    return@Button
                }
                scope.launch {
                    output = "Switch to another app now. Capturing in 5 seconds..."
                    delay(5000)
                    val screenText = svc.readScreenText().take(1200)
                    if (screenText.isBlank()) {
                        output = "No readable text found."
                        return@launch
                    }
                    output = "Captured ${screenText.length} chars. Summarizing on-device..."
                    runLlm("Summarize this phone screen in two sentences:\n$screenText") { summary ->
                        output = "SCREEN TEXT:\n$screenText\n\nSUMMARY:\n$summary"
                    }
                }
            }
        ) {
            Text("2. Read screen + summarize")
        }

        Button(onClick = { showConfirm = true }) {
            Text("3. Scroll down in other app")
        }

        Text(output)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Allow action?") },
            text = { Text("LocalAI will scroll down once in the app you switch to, after 5 seconds.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        output = "Switch to another app now. Scrolling in 5 seconds..."
                        delay(5000)
                        val ok = ScreenReaderService.instance?.scrollDown() ?: false
                        output = if (ok) "Scroll performed."
                        else "No scrollable view found, or service is off."
                    }
                }) { Text("Allow once") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}