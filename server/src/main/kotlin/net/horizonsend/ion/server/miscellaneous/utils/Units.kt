package net.horizonsend.ion.server.miscellaneous.utils

fun celsiusToKelvin(amountCelsius: Double): Double {
	return amountCelsius + 273.15
}

fun litersToCentimetersCubed(amountLiters: Double): Double {
	return amountLiters * 1000.0
}

fun centimetersCubedToLiters(amountCentimetersCubed: Double): Double {
	return amountCentimetersCubed / 1000.0
}
