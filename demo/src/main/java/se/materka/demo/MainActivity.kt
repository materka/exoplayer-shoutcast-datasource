package se.materka.demo

import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.util.Util
import okhttp3.OkHttpClient
import se.materka.exoplayershoutcastdatasource.Metadata
import se.materka.exoplayershoutcastdatasource.ShoutcastDataSourceFactory
import se.materka.exoplayershoutcastdatasource.ShoutcastMetadataListener


class MainActivity : AppCompatActivity(), ShoutcastMetadataListener {
    override fun onMetadataReceived(data: Metadata) {
        refreshMetadata("${data.artist} - ${data.song}")
    }

    private val player by lazy {
        ExoPlayerFactory.newSimpleInstance(applicationContext,
                DefaultTrackSelector(),
                DefaultLoadControl())
    }

    private var audioSource: MediaSource? = null

    private val dataSourceFactory: ShoutcastDataSourceFactory by lazy {
        ShoutcastDataSourceFactory(
                OkHttpClient.Builder().build(),
                Util.getUserAgent(applicationContext, getString(R.string.app_name)),
                null,
                this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.mp3).setOnClickListener {
            play("http://http-live.sr.se/p3-mp3-192")
        }

        findViewById<Button>(R.id.aac).setOnClickListener {
            play("http://radio.canstream.co.uk:8075/live.aac")
        }

        findViewById<Button>(R.id.ogg).setOnClickListener {
            play("http://revolutionradio.ru/live.ogg")
        }


        findViewById<Button>(R.id.stop).setOnClickListener {
            stop()
        }
    }

    private fun play(url: String) {
        // String with the url of the radio you want to play
        val uri = Uri.parse(url)

        // This is the MediaSource representing the media to be played.
        audioSource = ExtractorMediaSource(uri,
                dataSourceFactory, DefaultExtractorsFactory(), null, null)
        player.prepare(audioSource)
        player.playWhenReady = true
    }

    private fun stop() {
        player.stop()
         refreshMetadata("")
    }

    private fun refreshMetadata(metadata: String?) {
        findViewById<TextView>(R.id.metadata).post { (findViewById<TextView>(R.id.metadata) as TextView).text = metadata }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
