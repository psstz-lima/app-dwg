package com.appdwg.app

/**
 * Shader sources for the terrain view (OpenGL ES 3.00) and the camera background (OpenGL ES 2.0).
 * Kept here so a unit test can compile them with glslangValidator. `tuft` is the grass mottle:
 * `patch` is a reserved word on GLES 3.00 and Mali rejects the whole program, which leaves 3D blank.
 */
internal object GlShaders {
    val VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec3 aColor;
        uniform mat4 uMvp;
        uniform float uZScale;
        // Pulls lines towards the eye, so a line lying on the terrain is not hidden by it.
        uniform float uDepthBias;
        uniform int uLit;
        uniform int uGray;
        uniform int uSun;
        uniform vec3 uColor;
        // Picture over the surface: left x, top y, width and height, relative to the scene origin.
        uniform vec4 uDrapeRect;
        out vec3 vColor;
        out float vShade;
        out vec2 vUv;
        out vec3 vPos;
        void main() {
            gl_Position = uMvp * vec4(aPos.xy, aPos.z * uZScale, 1.0);
            gl_Position.z -= uDepthBias * gl_Position.w;
            vPos = aPos;
            vUv = vec2((aPos.x - uDrapeRect.x) / uDrapeRect.z, (uDrapeRect.y - aPos.y) / uDrapeRect.w);
            if (uLit == 1) {
                // Normal under a vertical stretch: (nx, ny, nz / s), normalised.
                vec3 n = normalize(vec3(aNormal.xy * uZScale, aNormal.z));
                // Two-sided: model faces may be wound either way, so both sides take the light.
                float light = abs(dot(n, normalize(vec3(-0.5, 0.5, 0.7))));
                vec3 tint = aColor;
                // Alto contraste is black on white. A dark green ramp reads as a flat black sheet,
                // so those faces are lifted to a warm tan and the light is stretched.
                if (uSun == 1) {
                    float y = dot(tint, vec3(0.30, 0.59, 0.11));
                    tint = mix(vec3(0.78, 0.66, 0.42), tint, clamp(y * 2.2, 0.2, 1.0));
                }
                vColor = uGray == 1 ? (uSun == 1 ? vec3(0.86, 0.78, 0.58) : vec3(0.82)) : tint;
                vShade = (uSun == 1 ? 0.25 : 0.45) + (uSun == 1 ? 0.95 : 0.65) * light;
            } else {
                vColor = uColor;
                vShade = 1.0;
            }
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;
        in vec3 vColor;
        in float vShade;
        // highp: at mediump (16 bits on Mali) a 2048 texture is read in steps of a whole texel,
        // so every quality looked blocky in 3D.
        in highp vec2 vUv;
        in vec3 vPos;
        uniform int uDrape;
        uniform sampler2D uTex;
        uniform float uAlpha;
        out vec4 fragColor;
        float grit(vec3 p) {
            return fract(sin(dot(floor(p * 37.0), vec3(127.1, 311.7, 74.7))) * 43758.5453);
        }
        void main() {
            vec3 c = vColor;
            if (uDrape == 1) {
                if (vUv.x < 0.0 || vUv.y < 0.0 || vUv.x > 1.0 || vUv.y > 1.0) discard;
                // Photos already carry their own shading, so the light is softened over them.
                vec4 t = texture(uTex, vUv);
                fragColor = vec4(mix(c * vShade, t.rgb * (0.7 + 0.3 * vShade), t.a), uAlpha);
                return;
            }
            float r = c.r;
            float g = c.g;
            float b = c.b;
            bool water = b > r + 0.12 && b > g;
            if (water) {
                fragColor = vec4(c * vShade, uAlpha);
                return;
            }
            float n = grit(vPos);
            float m = grit(vPos * 0.37 + vec3(19.0, 7.0, 3.0));
            bool grass = g > r + 0.05 && g >= b;
            bool earth = r > g + 0.10 && r > b + 0.08 && g > 0.25;
            bool stone = !grass && !earth && r > b + 0.02 && abs(r - g) < 0.12 && r < 0.70;
            bool metal = !grass && !earth && !stone && b + 0.02 >= r && b + 0.02 >= g && r < 0.75;
            vec3 tone = c;
            if (grass) {
                float tuft = grit(vPos * 3.5);
                tone = mix(c * vec3(0.78, 0.92, 0.72), c * vec3(0.95, 1.08, 0.80), tuft);
                tone += (n - 0.5) * 0.08;
            } else if (stone) {
                float course = step(0.90, fract(vPos.z * 14.0));
                float block = step(0.93, fract(vPos.x * 6.0 + floor(vPos.z * 14.0) * 0.5));
                tone = c * (0.86 + 0.20 * m) * (1.0 - 0.28 * max(course, block));
            } else if (earth) {
                tone = c * (0.88 + 0.24 * n) + vec3(0.04, 0.02, 0.0) * (m - 0.5);
            } else if (metal) {
                float streak = step(0.82, fract(vPos.x * 28.0 + vPos.y * 4.0));
                tone = c * (0.90 + 0.16 * n) * (1.0 - 0.18 * streak);
            } else {
                float board = step(0.96, fract(vPos.y * 5.0));
                float tie = smoothstep(0.05, 0.0, length(fract(vPos.xy * 2.5) - 0.5));
                tone = c * (0.94 + 0.10 * n) * (1.0 - 0.12 * board) - vec3(tie * 0.05);
            }
            float shade = vShade * (0.94 + 0.10 * n);
            fragColor = vec4(tone * shade, uAlpha);
        }
    """.trimIndent()

    /** Camera background. OpenGL ES 2.0 (`#version 100`). */
    val AR_VERTEX = """
        #version 100
        attribute vec4 aPos;
        attribute vec2 aTex;
        varying vec2 vTex;
        void main() { gl_Position = aPos; vTex = aTex; }
    """.trimIndent()

    val AR_FRAGMENT = """
        #version 100
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        uniform samplerExternalOES uTex;
        void main() { gl_FragColor = texture2D(uTex, vTex); }
    """.trimIndent()
}

/**
 * Reserved-word check for the GLSL ES sources. glslangValidator is also run when it is installed;
 * this catches the Mali failure (`patch` on ES 3.00) even on a machine without the binary.
 */
internal object GlslCheck {
    private val es300 = setOf(
        "active", "asm", "cast", "class", "common", "partition", "this", "union", "enum", "typedef",
        "template", "packed", "goto", "inline", "noinline", "volatile", "public", "static", "extern",
        "external", "interface", "long", "short", "double", "half", "fixed", "unsigned", "superp",
        "input", "output", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3", "dvec4", "fvec2", "fvec3",
        "fvec4", "filter", "sizeof", "namespace", "using", "sampler2DRect", "sampler3DRect",
        "sampler2DRectShadow", "resource", "noperspective", "subroutine", "patch", "sample",
    )
    private val es100 = setOf(
        "cast", "class", "enum", "typedef", "template", "this", "packed", "goto", "switch", "default",
        "inline", "noinline", "volatile", "public", "static", "extern", "external", "interface",
        "long", "short", "double", "half", "fixed", "unsigned", "superp", "input", "output",
        "hvec2", "hvec3", "hvec4", "dvec2", "dvec3", "dvec4", "fvec2", "fvec3", "fvec4",
        "sampler2DRect", "sizeof", "namespace", "using",
    )

    fun problems(source: String, esVersion: Int): List<String> {
        val banned = if (esVersion >= 300) es300 else es100
        val found = identifiers(source).filter { it in banned }.distinct()
        val out = ArrayList<String>()
        if (esVersion >= 300 && !source.contains("#version 300 es")) out += "missing #version 300 es"
        if (esVersion < 300 && !source.contains("#version 100")) out += "missing #version 100"
        for (word in found) out += "'$word': reserved word"
        return out
    }

    private fun identifiers(source: String): List<String> {
        val bare = source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), " ")
        return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(bare).map { it.value }.toList()
    }
}
