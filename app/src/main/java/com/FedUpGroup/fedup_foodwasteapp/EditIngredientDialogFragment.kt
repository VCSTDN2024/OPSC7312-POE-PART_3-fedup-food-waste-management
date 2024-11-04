package com.FedUpGroup.fedup_foodwasteapp

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.FedUpGroup.fedup_foodwasteapp.databinding.FragmentEditIngredientBinding
import java.util.Calendar

import android.util.Log

class EditIngredientDialogFragment : DialogFragment() {

    private var _binding: FragmentEditIngredientBinding? = null
    private val binding get() = _binding!!
    private val categories = Category.values()
    private var currentCategoryIndex = 0
    lateinit var ingredientViewModel: IngredientViewModel
    private lateinit var ingredient: Ingredient

    private var listener: ((Ingredient) -> Unit)? = null

    fun setOnSaveListener(listener: (Ingredient) -> Unit) {
        this.listener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            ingredient = it.getSerializable("ingredient") as Ingredient
            Log.d("EditIngredientDialog", "Ingredient loaded from arguments: $ingredient")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(R.color.grey)
        Log.d("EditIngredientDialog", "Dialog background color set to grey")
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditIngredientBinding.inflate(inflater, container, false)
        ingredientViewModel = ViewModelProvider(this).get(IngredientViewModel::class.java)
        Log.d("EditIngredientDialog", "ViewModel and binding initialized")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (::ingredient.isInitialized) {
            Log.d("EditIngredientDialog", "Populating ingredient data in UI components")
            binding.etIngredientName.setText(ingredient.productName)
            binding.etQuantity.setText(ingredient.quantity)
            binding.etExpirationDate.setText(ingredient.expirationDate ?: "YYYY-MM-DD")
            binding.tvCategory.text = ingredient.category
        } else {
            Log.e("EditIngredientDialog", "Ingredient not initialized, unable to populate UI components")
        }

        binding.etExpirationDate.setOnClickListener {
            Log.d("EditIngredientDialog", "Expiration date field clicked, showing date picker dialog")
            showDatePickerDialog()
        }

        binding.btnSaveIngredient.setOnClickListener {
            Log.d("EditIngredientDialog", "Save button clicked, capturing updated ingredient data")

            val updatedIngredient = Ingredient(
                id = ingredient.id,
                productName = binding.etIngredientName.text.toString(),
                quantity = binding.etQuantity.text.toString(),
                expirationDate = binding.etExpirationDate.text.toString(),
                category = binding.tvCategory.text.toString(),
                firebaseId = ingredient.firebaseId,
                userId = ingredient.userId,
                isSynced = false
            )
            Log.d("EditIngredientDialog", "Updated ingredient: $updatedIngredient")

            listener?.invoke(updatedIngredient)
            Log.d("EditIngredientDialog", "Listener invoked with updated ingredient")
            dismiss()
            Log.d("EditIngredientDialog", "Dialog dismissed after saving")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        Log.d("EditIngredientDialog", "View binding cleared")
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, selectedYear, selectedMonth, selectedDay ->
                val formattedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay)
                binding.etExpirationDate.setText(formattedDate)
                Log.d("EditIngredientDialog", "Date selected: $formattedDate")
            },
            year, month, day
        )

        datePickerDialog.show()
        Log.d("EditIngredientDialog", "Date picker dialog shown")
    }

    companion object {
        fun newInstance(ingredient: Ingredient): EditIngredientDialogFragment {
            val fragment = EditIngredientDialogFragment()
            val args = Bundle()
            args.putSerializable("ingredient", ingredient)
            fragment.arguments = args
            Log.d("EditIngredientDialog", "New instance created with ingredient: $ingredient")
            return fragment
        }
    }
}
