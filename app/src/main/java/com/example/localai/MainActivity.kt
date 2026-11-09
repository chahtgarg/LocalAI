
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

// Main entry point of the Android application.
class MainActivity : ComponentActivity() {

    companion object {
        init {
            // Loads the compiled native C++ library (liblocalai.so).
            System.loadLibrary("localai")
        }
    }

    // Kotlin declarations for functions implemented in native-lib.cpp.
    external fun loadModel(modelPath: String): String
    external fun generateResponse(prompt: String): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sets up the Jetpack Compose user interface.
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

// Main screen containing model selection, prompt input and response.
@Composable
fun ModelScreen(
    loadModel: (String) -> String,
    generateResponse: (String) -> String
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Creates a single background thread for model loading and inference.
    val executor = remember { Executors.newSingleThreadExecutor() }

    // Compose state variables: UI updates when these values change.
    var status by remember { mutableStateOf("Select a GGUF model to begin.") }
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }

    // Opens Android's document picker to select a model file.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->

        if (uri != null) {
            isBusy = true
            modelLoaded = false
            status = "Copying and loading model..."

            // File operations and model loading happen in the background.
            executor.execute {
                try {
                    // Copies the selected GGUF file into app-private storage.
                    val modelFile = File(context.filesDir, "selected-model.gguf")

                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open selected file." }

                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Sends the model's absolute path to the C++ layer.
                    val result = loadModel(modelFile.absolutePath)

                    // Updates the UI on Android's main thread.
                    (context as ComponentActivity).runOnUiThread {
                        status = result
                        modelLoaded = result.contains(
                            "successfully",
                            ignoreCase = true
                        )
                        isBusy = false
                    }

                } catch (e: Exception) {
                    // Displays any loading or file-copying error.
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

        // Button that launches the GGUF model file picker.
        Button(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            enabled = !isBusy
        ) {
            Text("Select GGUF Model")
        }

        // Shows the current model-loading status.
        Text(status)

        Divider()

        // Text field where the user enters a prompt.
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your prompt") },
            placeholder = { Text("Ask something...") }
        )

        // Sends the prompt to the native C++ inference function.
        Button(
            onClick = {
                if (prompt.isNotBlank() && modelLoaded) {

                    val question = prompt
                    isBusy = true
                    response = "Generating..."

                    // Runs inference in the background to avoid freezing the UI.
                    executor.execute {
                        val result = try {
                            generateResponse(question)
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }

                        // Displays the generated response on the main thread.
                        (context as ComponentActivity).runOnUiThread {
                            response = result
                            isBusy = false
                        }
                    }
                }
            },
            // Generate is enabled only when a model is loaded and input is valid.
            enabled = modelLoaded && !isBusy && prompt.isNotBlank()
        ) {
            Text("Generate")
        }

        // Displays the model's generated answer.
        Text("Response", style = MaterialTheme.typography.titleMedium)
        Text(
            text = response,
            modifier = Modifier.fillMaxWidth()
        )

        // Shows a loading indicator while an operation is running.
        if (isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Divider()

        Text("Accessibility", style = MaterialTheme.typography.titleLarge)

        // Adds the screen-reading and accessibility controls.
        AccessibilityPanel(
            canRunModel = modelLoaded && !isBusy,

            // Reuses the same native LLM generation function for screen summaries.
            runLlm = { text, onDone ->
                isBusy = true

                executor.execute {
                    val result = try {
                        generateResponse(text)
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }

                    // Returns the result to the UI thread.
                    (context as ComponentActivity).runOnUiThread {
                        isBusy = false
                        onDone(result)
                    }
                }
            }
        )
    }
}

// Separate UI section for accessibility-related functionality.
@Composable
fun AccessibilityPanel(
    canRunModel: Boolean,
    runLlm: (String, (String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Coroutine scope used for delayed accessibility actions.
    val scope = rememberCoroutineScope()

    var output by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Opens Android settings so the user can enable the accessibility service.
        Button(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }) {
            Text("1. Enable screen reader service")
        }

        // Reads accessible screen text and sends it to the local LLM.
        Button(
            enabled = canRunModel,
            onClick = {
                val svc = ScreenReaderService.instance

                // Checks whether the accessibility service is running.
                if (svc == null) {
                    output = "Service not enabled yet."
                    return@Button
                }

                scope.launch {
                    output = "Switch to another app now. Capturing in 5 seconds..."

                    // Gives the user time to switch to another app.
                    delay(5000)

                    // Reads screen text and limits it to 1200 characters.
                    val screenText = svc.readScreenText().take(1200)

                    if (screenText.isBlank()) {
                        output = "No readable text found."
                        return@launch
                    }

                    output = "Captured ${screenText.length} chars. Summarizing on-device..."

                    // Sends captured screen text to the locally running model.
                    runLlm(
                        "Summarize this phone screen in two sentences:\n$screenText"
                    ) { summary ->
                        output = "SCREEN TEXT:\n$screenText\n\nSUMMARY:\n$summary"
                    }
                }
            }
        ) {
            Text("2. Read screen + summarize")
        }

        // Requests confirmation before performing a scroll action.
        Button(onClick = { showConfirm = true }) {
            Text("3. Scroll down in other app")
        }

        Text(output)
    }

    // Confirmation dialog prevents the scroll action from happening immediately.
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },

            title = { Text("Allow action?") },

            text = {
                Text(
                    "LocalAI will scroll down once in the app you switch to, after 5 seconds."
                )
            },

            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false

                    scope.launch {
                        output = "Switch to another app now. Scrolling in 5 seconds..."

                        // Waits before asking the accessibility service to scroll.
                        delay(5000)

                        val ok = ScreenReaderService.instance?.scrollDown() ?: false

                        // Displays whether the scroll action succeeded.
                        output = if (ok) "Scroll performed."
                        else "No scrollable view found, or service is off."
                    }
                }) {
                    Text("Allow once")
                }
            },

            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
