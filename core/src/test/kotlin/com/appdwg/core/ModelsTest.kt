package com.appdwg.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun objWithMaterialsAndQuads() {
        val obj = "mtllib cor.mtl\no Caixa\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nusemtl verm\nf 1 2 3 4\n"
        val m = ModelReader.read(obj.toByteArray(), "caixa.obj") { if (it == "cor.mtl") "newmtl verm\nKd 1 0 0\n".toByteArray() else null }
        val mesh = m.meshes.single()
        assertEquals(2, mesh.triangleCount)
        assertEquals(0xFF0000, mesh.color)
        assertEquals("Caixa", mesh.layer)
    }

    @Test
    fun stlAsciiAndBinary() {
        val ascii = "solid t\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid t\n"
        assertEquals(1, ModelReader.read(ascii.toByteArray(), "a.stl").triangleCount)
        val b = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        b.position(80); b.putInt(1); b.position(84 + 12)
        floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f).forEach { b.putFloat(it) }
        val m = ModelReader.read(b.array(), "b.stl")
        assertEquals(2.0, m.meshes.single().positions[3], 1e-6)
    }

    @Test
    fun plyAscii() {
        val ply = "ply\nformat ascii 1.0\nelement vertex 4\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n1 0 0\n1 1 0\n0 1 0\n4 0 1 2 3\n"
        assertEquals(2, ModelReader.read(ply.toByteArray(), "p.ply").triangleCount)
    }

    @Test
    fun gltfWithEmbeddedBufferIsTurnedZUp() {
        val pos = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { pos.putFloat(it) }
        val data = java.util.Base64.getEncoder().encodeToString(pos.array())
        val json = """{"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0,"translation":[0,0,5]}],
            "meshes":[{"name":"tri","primitives":[{"attributes":{"POSITION":0}}]}],
            "buffers":[{"byteLength":36,"uri":"data:application/octet-stream;base64,$data"}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}]}"""
        val m = ModelReader.read(json.toByteArray(), "t.gltf")
        val p = m.meshes.single().positions
        // glTF Y-up (0,1,0) becomes Z-up; the node's +5 in glTF Z becomes −5 in project Y.
        assertEquals(1.0, p[8], 1e-6)
        assertEquals(-5.0, p[1], 1e-6)
    }

    @Test
    fun ifcWallAndSlabWithUnitsColoursStoreyAndGeoref() {
        val text = javaClass.classLoader.getResourceAsStream("models/parede.ifc")!!.readBytes()
        val m = ModelReader.read(text, "parede.ifc")
        assertEquals(0.001, m.unitScale, 1e-12)
        assertNotNull(m.georef)
        assertEquals(761000.0, m.georef!!.east, 1e-9)
        assertEquals(90.0, m.georef!!.rotationDeg, 1e-9)
        val wall = m.meshes.single { it.props["Tipo"] == "Wall" }
        assertEquals("IFC|Térreo|Wall", wall.layer)
        assertEquals(0xFF0000, wall.color)
        assertEquals(12, wall.triangleCount)
        // Wall placed at (1000, 2000) plus the profile's (2000, 100) centre: x from 1000 to 5000.
        val xs = (0 until wall.positions.size / 3).map { wall.positions[3 * it] }
        assertEquals(1000.0, xs.min(), 1e-6); assertEquals(5000.0, xs.max(), 1e-6)
        val zs = (0 until wall.positions.size / 3).map { wall.positions[3 * it + 2] }
        assertEquals(3000.0, zs.max(), 1e-6)
        val slab = m.meshes.single { it.props["Tipo"] == "Slab" }
        assertEquals("Laje É", slab.name)
        assertEquals(2, slab.triangleCount)
        assertTrue(m.warnings.isEmpty(), m.warnings.toString())
    }

    @Test
    fun placementScalesRotatesAndMoves() {
        val mesh = ModelMesh("L", "n", doubleArrayOf(1000.0, 0.0, 0.0), intArrayOf(), 0)
        val p = ModelPlacement(500000.0, 7000000.0, 100.0, rotationDeg = 90.0, unitScale = 0.001).apply(mesh)
        assertEquals(500000.0, p.positions[0], 1e-9)
        assertEquals(7000001.0, p.positions[1], 1e-9)
        assertEquals(100.0, p.positions[2], 1e-9)
    }

    @Test
    fun pickFindsTheHighestElementUnderThePoint() {
        val low = ModelMesh("A", "baixo", doubleArrayOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0), intArrayOf(0, 1, 2), 0)
        val high = ModelMesh("B", "alto", doubleArrayOf(0.0, 0.0, 5.0, 10.0, 0.0, 5.0, 0.0, 10.0, 5.0), intArrayOf(0, 1, 2), 0)
        assertEquals("alto", ModelPick.at(listOf(low, high), Point2(2.0, 2.0))!!.name)
        assertEquals("baixo", ModelPick.at(listOf(low, high), Point2(2.0, 2.0), setOf("B"))!!.name)
        assertEquals(null, ModelPick.at(listOf(low), Point2(9.0, 9.0)))
    }
}
