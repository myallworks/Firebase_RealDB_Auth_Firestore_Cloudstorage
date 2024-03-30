package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


class RegisterActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private lateinit var auth: FirebaseAuth
    private lateinit var binding: ActivityRegisterBinding


    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        launch(Dispatchers.IO) {
            binding.editTextEmail.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    s?.let {
                        val email = s.toString().trim { it <= ' ' }
                        checkEmailExistence(email)
                    }
                }
            })
        }

        binding.buttonRegister.setOnClickListener {
//            val username = binding.editTextUsername.text.toString().trim()
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            launch(Dispatchers.IO) {
                if (binding.buttonRegister.text.toString().length < 6) {
                    auth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener(this@RegisterActivity) { task ->
                            if (task.isSuccessful) {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Successfully Logged In",
                                    Toast.LENGTH_SHORT
                                )
                                    .show()
                                startActivity(
                                    Intent(
                                        this@RegisterActivity,
                                        ProfileActivity::class.java
                                    )
                                )
                                finish()
                            } else {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Login failed ->${task.exception}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                } else {
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener(this@RegisterActivity) { task ->
                            if (task.isSuccessful) {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Registration successful",
                                    Toast.LENGTH_SHORT
                                )
                                    .show()
                                startActivity(
                                    Intent(
                                        this@RegisterActivity,
                                        ProfileActivity::class.java
                                    )
                                )
                                finish()
                            } else {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Registration failed -> ${task.exception} ",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                }
            }
        }
    }


    private fun checkEmailExistence(email: String) {
        try {
            auth.fetchSignInMethodsForEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val result = task.result
                        if (result != null && result.signInMethods != null && result.signInMethods!!.size > 0) {
                            binding.buttonRegister.text = "Login"
                        } else {
                            if (binding.buttonRegister.text.toString().length < 6)
                                binding.buttonRegister.text = "Register"
                        }
                    }
                }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

}
