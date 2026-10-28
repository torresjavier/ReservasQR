package com.javier.reservas

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Lee un listado desde .xlsx o .csv y lo devuelve como tabla de textos. */
object LectorTabla {

    fun leer(bytes: ByteArray): List<List<String>> {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return XlsxReader.read(ByteArrayInputStream(bytes))
        }
        if (bytes.size >= 2 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) {
            throw IOException(
                "Este archivo es de Excel antiguo (.xls). Ábralo en Excel y use " +
                    "\"Guardar como\" → Libro de Excel (.xlsx), o guárdelo como CSV."
            )
        }
        return leerCsv(decodificar(bytes))
    }

    private fun decodificar(b: ByteArray): String {
        val datos = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b.copyOfRange(3, b.size)
        } else b
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(datos))
                .toString()
        } catch (e: CharacterCodingException) {
            // CSV guardado por Excel en Windows en español
            String(datos, Charset.forName("windows-1252"))
        }
    }

    private fun leerCsv(texto: String): List<List<String>> {
        val primeraLinea = texto.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val sep = listOf(';', ',', '\t').maxByOrNull { c -> primeraLinea.count { it == c } } ?: ';'

        val filas = mutableListOf<List<String>>()
        var fila = mutableListOf<String>()
        val celda = StringBuilder()
        var enComillas = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            if (enComillas) {
                if (c == '"') {
                    if (i + 1 < texto.length && texto[i + 1] == '"') {
                        celda.append('"'); i++
                    } else enComillas = false
                } else celda.append(c)
            } else {
                when (c) {
                    '"' -> enComillas = true
                    sep -> { fila.add(celda.toString()); celda.setLength(0) }
                    '\r' -> {}
                    '\n' -> {
                        fila.add(celda.toString()); celda.setLength(0)
                        filas.add(fila); fila = mutableListOf()
                    }
                    else -> celda.append(c)
                }
            }
            i++
        }
        if (celda.isNotEmpty() || fila.isNotEmpty()) {
            fila.add(celda.toString())
            filas.add(fila)
        }
        return filas
    }
}
