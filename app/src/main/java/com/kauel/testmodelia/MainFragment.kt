package com.kauel.testmodelia

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.kauel.testmodelia.databinding.FragmentMainBinding
import com.kauel.testmodelia.utils.ASSETS_NAME
import com.kauel.testmodelia.utils.FILE_NAME_ASSETS
import com.kauel.testmodelia.utils.gone
import com.kauel.testmodelia.utils.visible
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.torchvision.TensorImageUtils
import java.io.*
import java.util.*

class MainFragment : Fragment(R.layout.fragment_main) {

    private var binding: FragmentMainBinding? = null
    private lateinit var safeContext: Context

    private val pickImage = 100
    private var imageUri: Uri? = null
    private var mModule: Module? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        safeContext = context
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val fragmentBinding = FragmentMainBinding.bind(view)
        binding = fragmentBinding
        init()
        setUpView()
    }

    @SuppressLint("IntentReset")
    private fun setUpView() {
        binding?.apply {
            btnFindImage.setOnClickListener {
                val gallery =
                    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI)
                gallery.type = "image/*"
                startActivityForResult(gallery, pickImage)
            }
            btnProcessIA.setOnClickListener {
                tvTimeStart.text = getTimeNow()
                pbLoadIA.visible()
                val bitmap =
                    MediaStore.Images.Media.getBitmap(requireActivity().contentResolver,
                        imageUri)
                val path = getRealPathFromURI(imageUri, requireActivity())
                val orientation = getCameraPhotoOrientation(requireContext(),
                    imageUri, path)
                val rotated = rotateBitmap(bitmap, orientation.toFloat())
                val fileTemp = File(requireContext().cacheDir, "temp1")
                processIaImage(mBitmap = rotated!!,
                    imageView = imageView,
                    progressBar = pbLoadIA,
                    file = fileTemp,
                    timeEnd = tvTimeEnd)
            }
        }
    }

    private fun init() {
        loadModuleIA()
    }

    private fun loadModuleIA() {
        try {
            mModule = LiteModuleLoader.load(assetFilePath(safeContext, ASSETS_NAME))
            val br =
                BufferedReader(InputStreamReader(requireActivity().assets.open(FILE_NAME_ASSETS)))
            var classes: MutableList<String> = ArrayList()
            br.readLines().map { line ->
                classes.add(line)
            }
            PrePostProcessor.mClasses = classes.toTypedArray()
            classes = PrePostProcessor.mClasses.toMutableList()
        } catch (e: IOException) {
            Log.e("Object Detection", "Error reading assets", e)
        }
    }

    @Throws(IOException::class)
    private fun assetFilePath(context: Context, assetName: String?): String? {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }

        context.assets.open(assetName!!).use { `is` ->
            FileOutputStream(file).use { os ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (`is`.read(buffer).also { read = it } != -1) {
                    os.write(buffer, 0, read)
                }
                os.flush()
            }
            return file.absolutePath
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == pickImage) {
            imageUri = data?.data
            binding?.apply {
                imageView.setImageURI(imageUri)
                btnProcessIA.isEnabled = true
                tvTimeStart.text = ""
                tvTimeEnd.text = ""
            }
        }
    }

    private fun getTimeNow(): String {
        val rightNow = Calendar.getInstance()
        val hour: Int = rightNow.get(Calendar.HOUR_OF_DAY)
        val minute: Int = rightNow.get(Calendar.MINUTE)
        val second: Int = rightNow.get(Calendar.SECOND)
        return "$hour:$minute:$second"
    }

    private fun processIaImage(
        mBitmap: Bitmap,
        imageView: ImageView,
        progressBar: ProgressBar,
        file: File,
        timeEnd: TextView,
    ) {
        imageView.setImageBitmap(mBitmap)

        mBitmap.compress(Bitmap.CompressFormat.JPEG, 100, FileOutputStream(file))
        val canvas = Canvas(mBitmap)

        val mImgScaleX = mBitmap.width.toFloat() / PrePostProcessor.mInputWidth
        val mImgScaleY = mBitmap.height.toFloat() / PrePostProcessor.mInputHeight

        val mIvScaleX =
            if (mBitmap.width > mBitmap.height) canvas.width.toFloat() / mBitmap.width else canvas.height.toFloat() / mBitmap.height
        val mIvScaleY =
            if (mBitmap.height > mBitmap.width) canvas.height.toFloat() / mBitmap.height else canvas.width.toFloat() / mBitmap.width

        val mStartX = (canvas.width - mIvScaleX * mBitmap.width) / 2
        val mStartY = (canvas.height - mIvScaleY * mBitmap.height) / 2

        val timer: Thread = object : Thread() {
            @SuppressLint("SetTextI18n")
            override fun run() {
                val resizedBitmap = Bitmap.createScaledBitmap(mBitmap,
                    PrePostProcessor.mInputWidth,
                    PrePostProcessor.mInputHeight,
                    true)
                val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap,
                    PrePostProcessor.NO_MEAN_RGB,
                    PrePostProcessor.NO_STD_RGB)
                val outputTuple = mModule!!.forward(IValue.from(inputTensor)).toTuple()
                val outputTensor = outputTuple[0].toTensor()
                val outputs = outputTensor.dataAsFloatArray
                val results = PrePostProcessor.outputsToNMSPredictions(outputs,
                    mImgScaleX,
                    mImgScaleY,
                    mIvScaleX,
                    mIvScaleY,
                    mStartX,
                    mStartY)

                requireActivity().runOnUiThread(Runnable {
                    val mPaintRectangle = Paint()

                    for (result in results) {
                        mPaintRectangle.strokeWidth = 40f
                        mPaintRectangle.style = Paint.Style.STROKE
                        when (result.classIndex) {
                            1 -> {
                                mPaintRectangle.color = Color.GREEN
                            }
                            2 -> {
                                mPaintRectangle.color = Color.RED
                            }
                            0 -> {
                                mPaintRectangle.color = Color.YELLOW
                            }
                        }
                        canvas.drawRect(result.rect, mPaintRectangle)
                    }
                    progressBar.gone()
                    timeEnd.text = getTimeNow()
                })
            }
        }
        timer.start()
    }

    private fun getRealPathFromURI(uri: Uri?, activity: Activity): String? {
        val cursor: Cursor? =
            uri?.let { activity.contentResolver?.query(it, null, null, null, null) }
        cursor?.moveToFirst()
        val idx: Int? = cursor?.getColumnIndex(MediaStore.Images.ImageColumns.DATA)
        return idx?.let { cursor.getString(it) }
    }

    private fun getCameraPhotoOrientation(
        context: Context,
        imageUri: Uri?,
        imagePath: String?,
    ): Int {
        var rotate = 0
        try {
            context.contentResolver.notifyChange(imageUri!!, null)
            val imageFile = File(imagePath)
            val exif = ExifInterface(imageFile.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_270 -> rotate = 270
                ExifInterface.ORIENTATION_ROTATE_180 -> rotate = 180
                ExifInterface.ORIENTATION_ROTATE_90 -> rotate = 90
            }
            Log.i("RotateImage", "Exif orientation: $orientation")
            Log.i("RotateImage", "Rotate value: $rotate")
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return rotate
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap? {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}