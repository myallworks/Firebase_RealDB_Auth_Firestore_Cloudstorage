package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.myapplication.databinding.ActivityProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID


class ProfileActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private lateinit var currentUser: FirebaseUser
    private val PERMISSION_REQUEST_CODE = 10021
    private var databaseReference: DatabaseReference? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var binding: ActivityProfileBinding

    private var mImagePath: String? = null
    private val GALLERY = 1
    private var CAMERA = 2

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        currentUser = auth.currentUser!!
        databaseReference =
            currentUser?.uid?.let { FirebaseDatabase.getInstance().getReference("users").child(it) }

        getUserData()

        binding.buttonUpdateProfile.setOnClickListener {

            val name = binding.editTextName.text.toString().trim()
            val bio = binding.editTextBio.text.toString().trim()

            if (name.isEmpty() || bio.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            launch(Dispatchers.IO) {
                val userData = HashMap<String, Any>()
                userData["name"] = name
                userData["bio"] = bio

                currentUser?.let { user ->
                    databaseReference?.setValue(userData)
                        ?.addOnSuccessListener {
                            Log.d("RealtimeDB", "User profile data added successfully")
                            updateLogToFirestore(user.uid)
                        }
                        ?.addOnFailureListener { e ->
                            val errorMessage = e.message ?: "Unknown error"
                            Log.e(
                                "RealtimeDBError",
                                "Failed to add user profile data: $errorMessage",
                                e
                            )
                        }
                }
                uploadImageToFirebaseStorage()
            }
        }

        binding.cvProfileImageUpload.setOnClickListener {
            if (checkPermission()) {
                showPictureDialog()
            } else {
                requestPermission()
            }
        }
    }

    private fun updateLogToFirestore(uid: String) {
        uid?.let { id ->
            val userDocument =
                firestore.collection("logs").document()
            val logData = hashMapOf(
                "userID" to id,
                "timestamp" to FieldValue.serverTimestamp()
            )

            userDocument.set(logData)
                .addOnSuccessListener {
                    Log.d("FirestoreLog", "Log added successfully")
                    launch(Dispatchers.IO) {
                        getUserData()
                    }
                }
                .addOnFailureListener { e ->
                    val errorMessage = e.message ?: "Unknown error"
                    Log.e("FirestoreError", "Failed to add log: $errorMessage", e)
                }
        }

    }

    private fun getUserData() {
        databaseReference
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    if (dataSnapshot.exists()) {
                        for (childSnapshot in dataSnapshot.children) {
                            try {
                                val username =
                                    dataSnapshot.child("name").getValue(String::class.java)
                                val bio = dataSnapshot.child("bio").getValue(String::class.java)
                                val imageUrl =
                                    dataSnapshot.child("imageUrl").getValue(String::class.java)

                                binding.editTextName.setText(username)
                                binding.editTextBio.setText(bio)

                                imageUrl?.let { image ->
                                    Glide.with(binding.ivProfile.context)
                                        .load(Uri.parse(image))
                                        .into(binding.ivProfile)
                                }
                            } catch (ex: Exception) {
                                ex.printStackTrace()
                            }
                        }
                    } else {
                        Log.d("RealtimeDB", "No data found for user at specified time")
                    }
                }

                override fun onCancelled(databaseError: DatabaseError) {
                    Log.e("RealtimeDBError", "Error reading data: ${databaseError.message}")
                }
            })
    }


    private fun showPictureDialog() {
        val pictureDialog = AlertDialog.Builder(this)
        pictureDialog.setTitle("Select Action")
        val pictureDialogItems = arrayOf(
            "Select photo from Gallery",
            "Capture photo from Camera"
        )
        pictureDialog.setItems(
            pictureDialogItems
        ) { dialog, which ->
            when (which) {
                0 -> choosePhotoFromGallary()
                1 -> takePhotoFromCamera()
            }
        }
        pictureDialog.show()
    }

    fun choosePhotoFromGallary() {
        val galleryIntent = Intent(
            Intent.ACTION_PICK,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        )
        startActivityForResult(galleryIntent, GALLERY)
    }

    private fun takePhotoFromCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        startActivityForResult(intent, CAMERA)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_CANCELED) {
            return
        } else if (resultCode == Activity.RESULT_OK) {
            if (requestCode == GALLERY) {
                if (data != null) {
                    val contentURI = data.data
                    try {
                        val bitmap =
                            MediaStore.Images.Media.getBitmap(this@ProfileActivity.contentResolver, contentURI)

                        binding.ivProfile.setImageBitmap(bitmap)

                        val byteBuffer = ByteBuffer.allocate(bitmap.byteCount)
                        bitmap.copyPixelsToBuffer(byteBuffer)

                        mImagePath = contentURI?.let { getPath(this, it) }

                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            } else if (requestCode == CAMERA) {
                val thumbnail = data!!.extras!!["data"] as Bitmap?
                binding.ivProfile.setImageBitmap(thumbnail)

                mImagePath = data.data?.let { getPath(this, it) }

            }
        }
    }

    private fun checkPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
                if (ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    showMessageOKCancel(
                        "You need to allow access permissions"
                    ) { dialog, which ->
                        requestPermission()
                    }
                }
                if (ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    ) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    showMessageOKCancel(
                        "You need to allow access permissions"
                    ) { dialog, which ->
                        requestPermission()
                    }
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    showMessageOKCancel(
                        "You need to allow access permissions"
                    ) { dialog, which ->
                        requestPermission()
                    }
                }
            }
        }
    }


    private fun showMessageOKCancel(message: String, okListener: DialogInterface.OnClickListener) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", okListener)
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun uploadImageToFirebaseStorage() {
        mImagePath?.let { imagePath ->
            val storageRef = FirebaseStorage.getInstance().reference
            val imagesRef = storageRef.child("images/${UUID.randomUUID()}.jpg")

            imagesRef.putFile(Uri.parse(imagePath))
                .addOnSuccessListener { taskSnapshot ->
                    imagesRef.downloadUrl.addOnSuccessListener { uri ->
                        saveImageUrlToDatabase(uri.toString())
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(
                        "FirebaseStorage",
                        "Failed to upload image: ${exception.message}",
                        exception
                    )
                }
        }
    }

    fun getPath(context: Context?, uri: Uri): String? {

        if (DocumentsContract.isDocumentUri(context, uri)) {
            if ("com.android.providers.media.documents" == uri.authority) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                val type = split[0]
                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(
                    split[1]
                )
                return context?.let {
                    getDataColumn(
                        it,
                        contentUri,
                        selection,
                        selectionArgs
                    )
                }
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {

            // Return the remote address
            return if (("com.google.android.apps.photos.content" == uri.authority)) uri.lastPathSegment else context?.let {
                getDataColumn(
                    it,
                    uri,
                    null,
                    null
                )
            }
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }

    fun getDataColumn(
        context: Context, uri: Uri?, selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(
            column
        )
        try {
            cursor = context.contentResolver.query(
                uri!!, projection, selection, selectionArgs,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun saveImageUrlToDatabase(imageUrl: String) {
        val userData: Map<String, Any> = hashMapOf(
            "imageUrl" to imageUrl
        )

        databaseReference?.updateChildren(userData)
            ?.addOnSuccessListener {
                Log.d("FirebaseDatabase", "Image URL saved to database successfully")
            }
            ?.addOnFailureListener { exception ->
                Log.e(
                    "FirebaseDatabase",
                    "Failed to save image URL to database: ${exception.message}",
                    exception
                )
            }
    }
}
