package com.FedUpGroup.fedup_foodwasteapp

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Response

class AddIngredientFragment : DialogFragment() {

    lateinit var ingredientViewModel: IngredientViewModel
    private lateinit var tvCategory: TextView
    private lateinit var btnPlus: ImageButton
    private lateinit var btnMinus: ImageButton
    private var currentCategoryIndex = 0
    private lateinit var ingredientImageView: ImageView
    private var selectedIconResourceId: Int = R.drawable.ic_question_mark // Default icon

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_add_ingredient, container, false)
        dialog?.window?.setBackgroundDrawableResource(R.color.grey)

        ingredientViewModel = ViewModelProvider(this).get(IngredientViewModel::class.java)
        tvCategory = view.findViewById(R.id.tv_category)
        btnPlus = view.findViewById(R.id.btn_plus)
        btnMinus = view.findViewById(R.id.btn_minus)
        val expirationDateEditText = view.findViewById<EditText>(R.id.et_expiration_date)
        val categories = Category.values() // Use values() to get enum constants

        tvCategory.text = getString(categories[currentCategoryIndex].displayNameResourceId)

        // Plus button click
        btnPlus.setOnClickListener {
            currentCategoryIndex = (currentCategoryIndex + 1) % categories.size
            tvCategory.text = getString(categories[currentCategoryIndex].displayNameResourceId) // Update text with string resource
        }

        // Minus button click
        btnMinus.setOnClickListener {
            currentCategoryIndex = if (currentCategoryIndex - 1 < 0) {
                categories.size - 1
            } else {
                currentCategoryIndex - 1
            }
            tvCategory.text = getString(categories[currentCategoryIndex].displayNameResourceId) // Update text with string resource
        }

// In your Fragment
        val spinner = view.findViewById<Spinner>(R.id.spinnerUnit)
        val units = arrayOf("kg", "g", "lb", "oz", "L", "mL", "units")

        val textColor = ContextCompat.getColor(requireContext(), R.color.white)

// Use requireContext() instead of 'this' since we're in a Fragment
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, units.toList())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val selectedUnit = units[pos]
                (view as? TextView)?.setTextColor(textColor)            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Another interface callback
            }
        }

        expirationDateEditText.setOnClickListener {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            val datePickerDialog = DatePickerDialog(
                requireContext(),
                { _, selectedYear, selectedMonth, selectedDay ->
                    val formattedDate = String.format(
                        "%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay
                    )
                    expirationDateEditText.setText(formattedDate)
                },
                year, month, day
            )
            datePickerDialog.show()
        }

        view.findViewById<ImageButton>(R.id.btnSaveIngredient).setOnClickListener {
            val name = view.findViewById<EditText>(R.id.et_ingredient_name).text.toString()
            val expirationDate = expirationDateEditText.text.toString()
            val quantityValue = view.findViewById<EditText>(R.id.et_quantity).text.toString()
            val selectedUnit = spinner.selectedItem.toString()

            // Validate that quantity is not empty
            if (quantityValue.isEmpty()) {
                view.findViewById<EditText>(R.id.et_quantity).error = "Quantity is required"
                return@setOnClickListener
            }

            // Combine quantity and unit
            val quantity = "$quantityValue $selectedUnit"

            insertIngredient(
                name,
                quantity,  // This now contains both the number and unit (e.g. "500 g")
                getString(categories[currentCategoryIndex].displayNameResourceId), // Get category name from resources
                expirationDate,
                requireContext()
            )
        }

        // Observe insert result with detailed logging
        ingredientViewModel.insertResult.observe(viewLifecycleOwner, Observer { success ->
            if (success) {
                Log.d("IngredientFragment", "Ingredient was successfully added.")
                Toast.makeText(requireContext(), "Ingredient was added", Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Log.e("IngredientFragment", "Failed to add ingredient.")
                Toast.makeText(requireContext(), "Something went wrong", Toast.LENGTH_SHORT).show()
            }
        })

        ingredientImageView = view.findViewById(R.id.ingredientImageView)
        ingredientImageView.setOnClickListener {
            showIconSelector()
        }

        return view
    }

    private fun showIconSelector() {
        val iconSelector = IconSelectorDialog().apply {
            setOnIconSelectedListener { iconResId ->
                Log.d("IngredientFragment", "Icon selected with resource ID: $iconResId")
                ingredientImageView.setImageResource(iconResId)
            }
        }
        iconSelector.show(childFragmentManager, "iconSelector")
    }

    // Insert ingredient with logging
    fun insertIngredient(name: String, quantity: String, category: String, expirationDate: String, context: Context) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.w("IngredientFragment", "User not authenticated.")
            Snackbar.make(requireView(), "User not authenticated.", Snackbar.LENGTH_LONG).show()
            return
        }

        // Log input validation
        if (name.isBlank() || quantity.isBlank() || category.isBlank() || expirationDate.isBlank()) {
            Log.w("IngredientFragment", "Input validation failed. All fields are required.")
            Snackbar.make(requireView(), "All fields are required.", Snackbar.LENGTH_LONG).show()
            return
        }

        val ingredient = Ingredient(
            id = 0,
            productName = name,
            quantity = quantity,
            expirationDate = expirationDate,
            category = category,
            userId = user.uid,
            isSynced = false,
            version = 1,
            lastModified = System.currentTimeMillis()
        )
        Log.d("IngredientFragment", "Creating ingredient object: $ingredient")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (NetworkUtils.isNetworkAvailable(context)) {
                    Log.d("IngredientFragment", "Network available, proceeding with online insertion.")
                    handleOnlineInsertion(ingredient)
                } else {
                    Log.d("IngredientFragment", "No network, proceeding with offline insertion.")
                    handleOfflineInsertion(ingredient)
                }
            } catch (e: Exception) {
                Log.e("IngredientFragment", "Exception during insertion", e)
                withContext(Dispatchers.Main) { handleException(e) }
            }
        }
    }

    private suspend fun handleOnlineInsertion(ingredient: Ingredient) {
        try {
            Log.d("IngredientFragment", "Starting online insertion process.")

            val token = suspendCancellableCoroutine<String?> { continuation ->
                AuthManager.getInstance().getIdToken { token, error ->
                    if (error != null) {
                        Log.e("IngredientFragment", "Error retrieving token: ${error}")
                        continuation.resume(null) {}
                    } else {
                        Log.d("IngredientFragment", "Token retrieved successfully.")
                        continuation.resume(token) {}
                    }
                }
            }

            if (token == null) {
                Log.w("IngredientFragment", "Token is null, cannot proceed with API request.")
                withContext(Dispatchers.Main) {
                    Snackbar.make(requireView(), "Error retrieving token", Snackbar.LENGTH_LONG).show()
                }
                return
            }

            val roomId = ingredientViewModel.insertOffline(ingredient.copy(isSynced = false))
            Log.d("IngredientFragment", "Ingredient inserted into RoomDB with temporary ID: $roomId")

            val response = RetrofitClient.apiService.addIngredient(ingredient)
            if (response.isSuccessful) {
                val createdIngredient = response.body()
                if (createdIngredient != null) {
                    Log.d("IngredientFragment", "Ingredient successfully added to Firebase with ID: ${createdIngredient.firebaseId}")

                    val updatedIngredient = ingredient.copy(
                        id = roomId,
                        firebaseId = createdIngredient.firebaseId,
                        isSynced = true,
                        version = 1
                    )

                    val updatedFirebaseId = createdIngredient.firebaseId
                    ingredientViewModel.updateFirebaseIdOnly(roomId, updatedFirebaseId)
                    Log.d("IngredientFragment", "Updated RoomDB with Firebase ID: $updatedFirebaseId")

                    withContext(Dispatchers.Main) {
                        Snackbar.make(requireView(), "Ingredient added successfully!", Snackbar.LENGTH_LONG).show()
                        dismiss()
                    }
                }
            } else {
                Log.e("IngredientFragment", "Failed to add ingredient to Firebase. Response code: ${response.code()}")
                handleApiError(response)
            }
        } catch (e: Exception) {
            Log.e("IngredientFragment", "Exception during online insertion", e)
            withContext(Dispatchers.Main) { handleException(e) }
        }
    }

    private suspend fun handleOfflineInsertion(ingredient: Ingredient) {
        Log.d("IngredientFragment", "Inserting ingredient offline into RoomDB: $ingredient")
        ingredientViewModel.insertOffline(ingredient)

        withContext(Dispatchers.Main) {
            Snackbar.make(requireView(), "No network. Saved offline.", Snackbar.LENGTH_LONG).show()
            dismiss()
        }
    }
    // Helper function for error handling
    private suspend fun handleApiError(response: Response<Ingredient>) {
        val errorMessage = response.errorBody()?.string()
        withContext(Dispatchers.Main) {
            Snackbar.make(requireView(), "Error adding ingredient: $errorMessage", Snackbar.LENGTH_LONG).show()
        }
    }

    // Helper function for exception handling
    private suspend fun handleException(e: Exception) {
        withContext(Dispatchers.Main) {
            Snackbar.make(requireView(), "Exception: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showCustomSnackbar(message: String, ingredientId: String) {
        // Create Snackbar
        val snackbar = Snackbar.make(
            (context as Activity).findViewById(android.R.id.content),
            message,
            Snackbar.LENGTH_SHORT
        )

        // Customize Snackbar
        snackbar.setBackgroundTint(ContextCompat.getColor(context as Activity, R.color.darkgrey))
        snackbar.setActionTextColor(ContextCompat.getColor(context as Activity, R.color.white))

        // Get the TextView from the Snackbar and change the text color
        val snackbarView = snackbar.view
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(ContextCompat.getColor(context as Activity, R.color.red))

        // Show the Snackbar
        snackbar.show()
    }

}