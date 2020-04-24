package com.makor.hotornot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import com.makor.hotornot.classifier.*
import com.makor.hotornot.classifier.tensorflow.ImageClassifierFactory
import com.makor.hotornot.utils.getCroppedBitmap
import com.makor.hotornot.utils.getUriFromFilePath
import kotlinx.android.synthetic.main.activity_main.*
import okhttp3.*
import okhttp3.Request
import org.apache.commons.lang3.StringEscapeUtils
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.*

private const val REQUEST_PERMISSIONS = 1
private const val REQUEST_TAKE_PICTURE = 2

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val handler = Handler()
    private lateinit var classifier: Classifier
    private var photoFilePath = ""

    private var name = ""
    private var tts:TextToSpeech? = null
    private var btSpeak: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        tts = TextToSpeech(this, this)
        btSpeak = this.bt_details
        btSpeak!!.isEnabled = false
    }

    private fun checkPermissions() {
        if (arePermissionsAlreadyGranted()) {
            init()
        } else {
            requestPermissions()
        }
    }

    private fun arePermissionsAlreadyGranted() =
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSIONS && arePermissionGranted(grantResults)) {
            init()
        } else {
            requestPermissions()
        }
    }

    private fun arePermissionGranted(grantResults: IntArray) =
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

    private fun init() {
        createClassifier()
        takePhoto()
    }

    private fun createClassifier() {
        classifier = ImageClassifierFactory.create(
                assets,
                GRAPH_FILE_PATH,
                LABELS_FILE_PATH,
                IMAGE_SIZE,
                GRAPH_INPUT_NAME,
                GRAPH_OUTPUT_NAME
        )
    }

    private fun takePhoto() {
        tv_dt.text = ""

        photoFilePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/${System.currentTimeMillis()}.jpg"
        val currentPhotoUri = getUriFromFilePath(this, photoFilePath)

        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri)
        takePictureIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        if (takePictureIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(takePictureIntent, REQUEST_TAKE_PICTURE)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.activity_main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.take_photo) {
            takePhoto()
            this.tts!!.stop()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val file = File(photoFilePath)
        if (requestCode == REQUEST_TAKE_PICTURE && file.exists()) {
            classifyPhoto(file)
        }
    }

    private fun classifyPhoto(file: File) {
        val photoBitmap = BitmapFactory.decodeFile(file.absolutePath)
        val croppedBitmap = getCroppedBitmap(photoBitmap)
        classifyAndShowResult(croppedBitmap)
        imagePhoto.setImageBitmap(photoBitmap)
    }

    private fun classifyAndShowResult(croppedBitmap: Bitmap) {
        runInBackground(
                Runnable {
                    val result = classifier.recognizeImage(croppedBitmap)
                    showResult(result)
                })
    }



    public fun showDetails(view: View) {
        val newTSS = this.tts


        val encodededName = URLEncoder.encode(this.name, "UTF-8")
        val googleUrl = "https://www.google.com/search?q=" + encodededName + "wikipedia+english"
        val request = Request.Builder().url(googleUrl).get().build()
        val client = OkHttpClient()

        client.newCall(request).enqueue(object: Callback {

            override fun onResponse(call: Call, response: Response) {
                val body = response?.body()?.string()
                val fLink = Jsoup.parse(body).getElementsByTag("cite")[0].text()

                val lPart = URLEncoder.encode(fLink.substring(fLink.lastIndexOf("/") + 1, fLink.length), "UTF-8")
                val wLink = "https://www.wikipedia.org/w/api.php?format=json&action=query&prop=extracts&exintro=&explaintext=&exsentences=5&titles=" + lPart
                val wRequest = Request.Builder().url(wLink).build()

                client.newCall(wRequest).enqueue(object: Callback {
                    override fun onResponse(call: Call, response: Response) {
                        val wBody = response?.body()?.string()
                        var result = wBody!!.split("extract\":\"")[1]
                        result = result.replace("\"}}}}","")
                        val showText = StringEscapeUtils.unescapeJson(result)

                        runOnUiThread {
                            tv_dt.text = showText

                            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                newTSS!!.speak(showText, TextToSpeech.QUEUE_FLUSH, null, null)
                            } else {
                                val hash = HashMap<String, String>()
                                hash.put(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION.toString())
                                newTSS!!.speak(showText, TextToSpeech.QUEUE_FLUSH, hash)
                            }
                        }
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        println("Failed to execute request")
                    }
                })

            }

            override fun onFailure(call: Call, e: IOException) {
//                println("Failed to execute request")
                runOnUiThread {
                    tv_dt.text = "Please connect your internet connection..."
                }
            }

        })

    }

    override fun onInit(status: Int) {

        if(status == TextToSpeech.SUCCESS) {
            val speed = 0.7f
            val speakText = tts!!.setLanguage(Locale.US)
            tts!!.setSpeechRate(speed)

            if(speakText != TextToSpeech.LANG_MISSING_DATA || speakText != TextToSpeech.LANG_NOT_SUPPORTED) {
                btSpeak!!.isEnabled = true
            }
        }
    }

    public override fun onDestroy() {
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }

        super.onDestroy()
    }

    @Synchronized
    private fun runInBackground(runnable: Runnable) {
        handler.post(runnable)
    }

    private fun showResult(result: Result) {
        textResult.text = result.result.toUpperCase()
        this.name = result.result
        layoutContainer.setBackgroundColor(getColorFromResult(result.result))
    }

    @Suppress("DEPRECATION")
    private fun getColorFromResult(result: String): Int {
        return if (result != getString(R.string.not)) {
            resources.getColor(R.color.hot)
        } else {
            resources.getColor(R.color.not)
        }
    }
}