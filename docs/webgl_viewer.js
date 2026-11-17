/**
 * MythicWardrobe Studio — Zero-Dependency WebGL 1.0 Minecraft & BBModel 3D Renderer
 * Supports:
 *   - 64x64 Minecraft Skins (Wide 4px & Slim 3px arms, Inner + Outer 2nd layer)
 *   - Real .bbmodel cubes with per-face UV mapping, pivot rotations (ZYX), bone rigging
 *   - MythicArmors _t.png live RGB tinting & _e.png emissive glow
 *   - Smooth orbit camera (drag rotate, scroll zoom, right-drag pan, presets)
 *   - Walk animation proving bone rigging works on arms/legs/head/body!
 */
(function (global) {
  "use strict";

  const DEG = Math.PI / 180;

  const BONE_PIVOTS = {
    head:       [0, 24, 0],
    body:       [0, 24, 0],
    right_arm:  [5, 22, 0],
    left_arm:   [-5, 22, 0],
    waist:      [0, 12, 0],
    right_leg:  [2, 12, 0],
    left_leg:   [-2, 12, 0],
    right_foot: [2, 12, 0],
    left_foot:  [-2, 12, 0],
  };

  // --- Minimal 4x4 Column-Major Matrix Math ---
  function mat4Identity() {
    return new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
  }
  function mat4Mul(a, b) {
    const o = new Float32Array(16);
    for (let c = 0; c < 4; c++) {
      for (let r = 0; r < 4; r++) {
        o[c * 4 + r] =
          a[0 * 4 + r] * b[c * 4 + 0] +
          a[1 * 4 + r] * b[c * 4 + 1] +
          a[2 * 4 + r] * b[c * 4 + 2] +
          a[3 * 4 + r] * b[c * 4 + 3];
      }
    }
    return o;
  }
  function mat4Perspective(fovRad, aspect, near, far) {
    const f = 1.0 / Math.tan(fovRad / 2);
    const nf = 1.0 / (near - far);
    return new Float32Array([
      f / aspect, 0, 0, 0,
      0, f, 0, 0,
      0, 0, (far + near) * nf, -1,
      0, 0, 2 * far * near * nf, 0,
    ]);
  }
  function mat4LookAt(eye, target, up) {
    let zx = eye[0] - target[0], zy = eye[1] - target[1], zz = eye[2] - target[2];
    let zl = Math.hypot(zx, zy, zz) || 1;
    zx /= zl; zy /= zl; zz /= zl;
    let xx = up[1] * zz - up[2] * zy;
    let xy = up[2] * zx - up[0] * zz;
    let xz = up[0] * zy - up[1] * zx;
    let xl = Math.hypot(xx, xy, xz) || 1;
    xx /= xl; xy /= xl; xz /= xl;
    const yx = zy * xz - zz * xy;
    const yy = zz * xx - zx * xz;
    const yz = zx * xy - zy * xx;
    return new Float32Array([
      xx, yx, zx, 0,
      xy, yy, zy, 0,
      xz, yz, zz, 0,
      -(xx * eye[0] + xy * eye[1] + xz * eye[2]),
      -(yx * eye[0] + yy * eye[1] + yz * eye[2]),
      -(zx * eye[0] + zy * eye[1] + zz * eye[2]),
      1,
    ]);
  }
  function mat4Translate(x, y, z) {
    const m = mat4Identity();
    m[12] = x; m[13] = y; m[14] = z;
    return m;
  }
  function mat4RotX(rad) {
    const c = Math.cos(rad), s = Math.sin(rad);
    return new Float32Array([1,0,0,0, 0,c,s,0, 0,-s,c,0, 0,0,0,1]);
  }
  function mat4RotY(rad) {
    const c = Math.cos(rad), s = Math.sin(rad);
    return new Float32Array([c,0,-s,0, 0,1,0,0, s,0,c,0, 0,0,0,1]);
  }
  function mat4RotZ(rad) {
    const c = Math.cos(rad), s = Math.sin(rad);
    return new Float32Array([c,s,0,0, -s,c,0,0, 0,0,1,0, 0,0,0,1]);
  }

  function rotatePointEulerZYX(pt, origin, rotDeg) {
    if (!rotDeg || (rotDeg[0] === 0 && rotDeg[1] === 0 && rotDeg[2] === 0)) return pt;
    let x = pt[0] - origin[0];
    let y = pt[1] - origin[1];
    let z = pt[2] - origin[2];
    const rx = (rotDeg[0] || 0) * DEG;
    const ry = (rotDeg[1] || 0) * DEG;
    const rz = (rotDeg[2] || 0) * DEG;
    if (rx !== 0) {
      const c = Math.cos(rx), s = Math.sin(rx);
      const ny = y * c - z * s, nz = y * s + z * c;
      y = ny; z = nz;
    }
    if (ry !== 0) {
      const c = Math.cos(ry), s = Math.sin(ry);
      const nx = x * c + z * s, nz = -x * s + z * c;
      x = nx; z = nz;
    }
    if (rz !== 0) {
      const c = Math.cos(rz), s = Math.sin(rz);
      const nx = x * c - y * s, ny = x * s + y * c;
      x = nx; y = ny;
    }
    return [x + origin[0], y + origin[1], z + origin[2]];
  }

  function appendBoxGeometry(arr, from, to, facesUV, texW, texH, origin, rotDeg, inflate, isEmissiveFlag) {
    const inf = inflate || 0;
    const x0 = Math.min(from[0], to[0]) - inf;
    const y0 = Math.min(from[1], to[1]) - inf;
    const z0 = Math.min(from[2], to[2]) - inf;
    const x1 = Math.max(from[0], to[0]) + inf;
    const y1 = Math.max(from[1], to[1]) + inf;
    const z1 = Math.max(from[2], to[2]) + inf;
    const orig = origin || [(x0 + x1) * 0.5, (y0 + y1) * 0.5, (z0 + z1) * 0.5];

    // 6 faces matching Blockbench coordinate convention (Front = North / -Z, Right = +X)
    const faceDefs = {
      north: { n: [0, 0, -1], pts: [[x1,y1,z0], [x0,y1,z0], [x0,y0,z0], [x1,y0,z0]] },
      south: { n: [0, 0,  1], pts: [[x0,y1,z1], [x1,y1,z1], [x1,y0,z1], [x0,y0,z1]] },
      east:  { n: [1, 0,  0], pts: [[x1,y1,z1], [x1,y1,z0], [x1,y0,z0], [x1,y0,z1]] },
      west:  { n: [-1,0,  0], pts: [[x0,y1,z0], [x0,y1,z1], [x0,y0,z1], [x0,y0,z0]] },
      up:    { n: [0, 1,  0], pts: [[x1,y1,z1], [x0,y1,z1], [x0,y1,z0], [x1,y1,z0]] },
      down:  { n: [0,-1,  0], pts: [[x1,y0,z0], [x0,y0,z0], [x0,y0,z1], [x1,y0,z1]] },
    };

    const invW = 1.0 / Math.max(1, texW);
    const invH = 1.0 / Math.max(1, texH);

    for (const dir of Object.keys(faceDefs)) {
      const f = facesUV[dir];
      if (!f) continue;
      const uv = Array.isArray(f) ? f : f.uv;
      if (!uv || uv.length < 4) continue;
      if (Math.abs(uv[2] - uv[0]) < 1e-5 || Math.abs(uv[3] - uv[1]) < 1e-5) continue;

      const u1 = uv[0] * invW, v1 = uv[1] * invH;
      const u2 = uv[2] * invW, v2 = uv[3] * invH;
      let uvs = [[u1, v1], [u2, v1], [u2, v2], [u1, v2]];

      // Optional Blockbench per-face UV rotation (0, 90, 180, 270)
      const frot = (!Array.isArray(f) && f.rotation) ? ((f.rotation % 360) + 360) % 360 : 0;
      if (frot === 90) uvs = [uvs[3], uvs[0], uvs[1], uvs[2]];
      else if (frot === 180) uvs = [uvs[2], uvs[3], uvs[0], uvs[1]];
      else if (frot === 270) uvs = [uvs[1], uvs[2], uvs[3], uvs[0]];

      const fd = faceDefs[dir];
      const pts = fd.pts.map(p => rotatePointEulerZYX(p, orig, rotDeg));
      const nrm = rotatePointEulerZYX(fd.n, [0, 0, 0], rotDeg);
      const nl = Math.hypot(nrm[0], nrm[1], nrm[2]) || 1;
      const nx = nrm[0] / nl, ny = nrm[1] / nl, nz = nrm[2] / nl;

      // Two triangles: (0,1,2) and (0,2,3)
      const idxs = [0, 1, 2, 0, 2, 3];
      for (const i of idxs) {
        arr.push(
          pts[i][0], pts[i][1], pts[i][2],
          nx, ny, nz,
          uvs[i][0], uvs[i][1],
          isEmissiveFlag ? 1.0 : 0.0
        );
      }
    }
  }

  // Standard 64x64 Minecraft Skin UV helpers: box at texture origin (tx, ty) of pixel dimensions (w, h, d)
  function mcBoxUVs(tx, ty, w, h, d) {
    return {
      up:    [tx + d,         ty,     tx + d + w,         ty + d],
      down:  [tx + d + w,     ty + d, tx + d + 2 * w,     ty],
      east:  [tx,             ty + d, tx + d,             ty + d + h],
      north: [tx + d,         ty + d, tx + d + w,         ty + d + h],
      west:  [tx + d + w,     ty + d, tx + 2 * d + w,     ty + d + h],
      south: [tx + 2 * d + w, ty + d, tx + 2 * d + 2 * w, ty + d + h],
    };
  }

  function buildMinecraftPlayerMeshes(slim, showOuter) {
    const armW = slim ? 3 : 4;
    const byBone = {
      head: [], body: [], right_arm: [], left_arm: [], right_leg: [], left_leg: [],
    };
    // Inner body
    appendBoxGeometry(byBone.head,      [-4, 24, -4], [4, 32, 4],        mcBoxUVs(0, 0, 8, 8, 8),       64, 64);
    appendBoxGeometry(byBone.body,      [-4, 12, -2], [4, 24, 2],        mcBoxUVs(16, 16, 8, 12, 4),    64, 64);
    appendBoxGeometry(byBone.right_arm, [4, 12, -2],  [4 + armW, 24, 2], mcBoxUVs(40, 16, armW, 12, 4), 64, 64);
    appendBoxGeometry(byBone.left_arm,  [-4 - armW, 12, -2], [-4, 24, 2], mcBoxUVs(32, 48, armW, 12, 4), 64, 64);
    appendBoxGeometry(byBone.right_leg, [0, 0, -2],   [4, 12, 2],        mcBoxUVs(0, 16, 4, 12, 4),     64, 64);
    appendBoxGeometry(byBone.left_leg,  [-4, 0, -2],  [0, 12, 2],        mcBoxUVs(16, 48, 4, 12, 4),    64, 64);

    // Outer 2nd skin layer
    if (showOuter) {
      appendBoxGeometry(byBone.head,      [-4, 24, -4], [4, 32, 4],         mcBoxUVs(32, 0, 8, 8, 8),       64, 64, null, null, 0.48);
      appendBoxGeometry(byBone.body,      [-4, 12, -2], [4, 24, 2],         mcBoxUVs(16, 32, 8, 12, 4),     64, 64, null, null, 0.26);
      appendBoxGeometry(byBone.right_arm, [4, 12, -2],  [4 + armW, 24, 2],  mcBoxUVs(40, 32, armW, 12, 4),  64, 64, null, null, 0.26);
      appendBoxGeometry(byBone.left_arm,  [-4 - armW, 12, -2], [-4, 24, 2], mcBoxUVs(48, 48, armW, 12, 4),  64, 64, null, null, 0.26);
      appendBoxGeometry(byBone.right_leg, [0, 0, -2],   [4, 12, 2],         mcBoxUVs(0, 32, 4, 12, 4),      64, 64, null, null, 0.26);
      appendBoxGeometry(byBone.left_leg,  [-4, 0, -2],  [0, 12, 2],         mcBoxUVs(0, 48, 4, 12, 4),      64, 64, null, null, 0.26);
    }
    return byBone;
  }

  function makeDefaultMannequinCanvas() {
    const c = document.createElement("canvas");
    c.width = 64; c.height = 64;
    const ctx = c.getContext("2d");
    // Clean neutral studio mannequin texture (subtle shading + face features)
    ctx.fillStyle = "#c8b9a6"; ctx.fillRect(0, 0, 32, 16);
    // Subtle hair cap & eyes
    ctx.fillStyle = "#5c4a3e"; ctx.fillRect(8, 8, 8, 2); ctx.fillRect(0, 8, 8, 2); ctx.fillRect(16, 8, 16, 3); ctx.fillRect(8, 0, 8, 8);
    ctx.fillStyle = "#ffffff"; ctx.fillRect(9, 11, 2, 1); ctx.fillRect(13, 11, 2, 1);
    ctx.fillStyle = "#3a6ea5"; ctx.fillRect(10, 11, 1, 1); ctx.fillRect(13, 11, 1, 1);
    ctx.fillStyle = "#a78c78"; ctx.fillRect(11, 13, 2, 1);
    // Neutral studio gray shirt & pants
    ctx.fillStyle = "#6c7689"; ctx.fillRect(16, 16, 24, 16);
    ctx.fillStyle = "#7a8599"; ctx.fillRect(40, 16, 16, 16); ctx.fillRect(32, 48, 16, 16);
    ctx.fillStyle = "#c8b9a6"; ctx.fillRect(40, 26, 16, 6); ctx.fillRect(32, 58, 16, 6);
    ctx.fillStyle = "#485163"; ctx.fillRect(0, 16, 16, 16); ctx.fillRect(16, 48, 16, 16);
    ctx.fillStyle = "#2d3340"; ctx.fillRect(0, 29, 16, 3); ctx.fillRect(16, 61, 16, 3);
    return c;
  }

  const VS_SRC = `
    attribute vec3 aPos;
    attribute vec3 aNorm;
    attribute vec2 aUv;
    attribute float aEmis;
    uniform mat4 uProj;
    uniform mat4 uView;
    uniform mat4 uModel;
    varying vec3 vNorm;
    varying vec2 vUv;
    varying float vEmis;
    void main() {
      gl_Position = uProj * uView * uModel * vec4(aPos, 1.0);
      vNorm = mat3(uModel) * aNorm;
      vUv = aUv;
      vEmis = aEmis;
    }
  `;

  const FS_SRC = `
    precision mediump float;
    uniform sampler2D uTex;
    uniform float uAlphaCutoff;
    uniform float uAmbient;
    uniform float uDiffuse;
    uniform float uEmissiveBoost;
    varying vec3 vNorm;
    varying vec2 vUv;
    varying float vEmis;
    void main() {
      vec4 col = texture2D(uTex, vUv);
      if (col.a < uAlphaCutoff) discard;
      vec3 n = normalize(vNorm);
      float light1 = max(dot(n, normalize(vec3(0.45, 0.78, -0.52))), 0.0);
      float light2 = max(dot(n, normalize(vec3(-0.55, 0.35, 0.72))), 0.0);
      float shade = clamp(uAmbient + light1 * uDiffuse + light2 * (uDiffuse * 0.42), 0.14, 1.16);
      vec3 rgb = mix(col.rgb * shade, col.rgb * uEmissiveBoost, vEmis);
      gl_FragColor = vec4(rgb, col.a);
    }
  `;

  const LINE_VS = `
    attribute vec3 aPos;
    attribute vec3 aCol;
    uniform mat4 uProj;
    uniform mat4 uView;
    varying vec3 vCol;
    void main() {
      gl_Position = uProj * uView * vec4(aPos, 1.0);
      vCol = aCol;
    }
  `;
  const LINE_FS = `
    precision mediump float;
    varying vec3 vCol;
    void main() { gl_FragColor = vec4(vCol, 1.0); }
  `;

  class Studio3DViewer {
    constructor(canvas) {
      this.canvas = canvas;
      this.gl = canvas.getContext("webgl", { alpha: true, antialias: true, preserveDrawingBuffer: true })
             || canvas.getContext("experimental-webgl", { alpha: true, antialias: true });
      this.yaw = -0.52;
      this.pitch = 0.18;
      this.dist = 56;
      this.targetY = 17;
      this.autoSpin = false;
      this.poseMode = "idle"; // "idle" | "walk" | "run" | "sneak" | "apose"
      this.lightMode = "studio"; // "studio" | "day" | "night"
      this.showPlayer = true;
      this.showArmor = true;
      this.showOuterSkin = true;
      this.showGrid = true;
      this.showBones = false;
      this.slim = false;
      this.t = 0;

      this.playerMeshes = {};
      this.playerTex = null;
      this.bbDrawList = []; // [{bone, buffer, vertexCount, tex, emissiveBuffer, emissiveCount, emissiveTex}]
      this.gridBuffer = null;
      this.gridCount = 0;
      this.boneGizmoBuffer = null;
      this.boneGizmoCount = 0;

      if (this.gl) {
        this._initGL();
        this._rebuildPlayerGeometry();
        this.setSkinCanvas(makeDefaultMannequinCanvas());
        this._bindPointer();
        this._startLoop();
      }
    }

    _compile(type, src) {
      const gl = this.gl;
      const sh = gl.createShader(type);
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      return sh;
    }

    _createProg(vs, fs) {
      const gl = this.gl;
      const p = gl.createProgram();
      gl.attachShader(p, this._compile(gl.VERTEX_SHADER, vs));
      gl.attachShader(p, this._compile(gl.FRAGMENT_SHADER, fs));
      gl.linkProgram(p);
      return p;
    }

    _initGL() {
      const gl = this.gl;
      this.prog = this._createProg(VS_SRC, FS_SRC);
      this.loc = {
        aPos: gl.getAttribLocation(this.prog, "aPos"),
        aNorm: gl.getAttribLocation(this.prog, "aNorm"),
        aUv: gl.getAttribLocation(this.prog, "aUv"),
        aEmis: gl.getAttribLocation(this.prog, "aEmis"),
        uProj: gl.getUniformLocation(this.prog, "uProj"),
        uView: gl.getUniformLocation(this.prog, "uView"),
        uModel: gl.getUniformLocation(this.prog, "uModel"),
        uTex: gl.getUniformLocation(this.prog, "uTex"),
        uAlphaCutoff: gl.getUniformLocation(this.prog, "uAlphaCutoff"),
        uAmbient: gl.getUniformLocation(this.prog, "uAmbient"),
        uDiffuse: gl.getUniformLocation(this.prog, "uDiffuse"),
        uEmissiveBoost: gl.getUniformLocation(this.prog, "uEmissiveBoost"),
      };
      this.lineProg = this._createProg(LINE_VS, LINE_FS);
      this.lineLoc = {
        aPos: gl.getAttribLocation(this.lineProg, "aPos"),
        aCol: gl.getAttribLocation(this.lineProg, "aCol"),
        uProj: gl.getUniformLocation(this.lineProg, "uProj"),
        uView: gl.getUniformLocation(this.lineProg, "uView"),
      };

      gl.enable(gl.DEPTH_TEST);
      gl.depthFunc(gl.LEQUAL);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

      // Floor monochrome studio grid
      const lines = [];
      for (let i = -16; i <= 16; i += 4) {
        const col = i === 0 ? [0.38, 0.38, 0.38] : [0.16, 0.16, 0.16];
        lines.push(i, 0, -16, ...col, i, 0, 16, ...col);
        lines.push(-16, 0, i, ...col, 16, 0, i, ...col);
      }
      // Crisp white "FRONT (-Z)" arrow on floor so user always sees front vs back!
      lines.push(0, 0.05, -10, 0.92, 0.92, 0.92, 0, 0.05, -16, 0.92, 0.92, 0.92);
      lines.push(-2, 0.05, -13.5, 0.92, 0.92, 0.92, 0, 0.05, -16, 0.92, 0.92, 0.92);
      lines.push(2, 0.05, -13.5, 0.92, 0.92, 0.92, 0, 0.05, -16, 0.92, 0.92, 0.92);

      this.gridBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.gridBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(lines), gl.STATIC_DRAW);
      this.gridCount = lines.length / 6;

      // Bone pivot crosshairs
      const giz = [];
      for (const [bname, pv] of Object.entries(BONE_PIVOTS)) {
        const s = 2.2;
        giz.push(pv[0]-s, pv[1], pv[2], 1,0.35,0.35, pv[0]+s, pv[1], pv[2], 1,0.35,0.35);
        giz.push(pv[0], pv[1]-s, pv[2], 0.35,1,0.5, pv[0], pv[1]+s, pv[2], 0.35,1,0.5);
        giz.push(pv[0], pv[1], pv[2]-s, 0.35,0.65,1, pv[0], pv[1], pv[2]+s, 0.35,0.65,1);
      }
      this.boneGizmoBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.boneGizmoBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(giz), gl.STATIC_DRAW);
      this.boneGizmoCount = giz.length / 6;
    }

    _uploadTexture(sourceCanvasOrImage) {
      const gl = this.gl;
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, sourceCanvasOrImage);
      return tex;
    }

    _rebuildPlayerGeometry() {
      const gl = this.gl;
      if (!gl) return;
      for (const m of Object.values(this.playerMeshes)) {
        if (m.buffer) gl.deleteBuffer(m.buffer);
      }
      this.playerMeshes = {};
      const byBone = buildMinecraftPlayerMeshes(this.slim, this.showOuterSkin);
      for (const [bone, verts] of Object.entries(byBone)) {
        if (!verts.length) continue;
        const buf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, buf);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(verts), gl.STATIC_DRAW);
        this.playerMeshes[bone] = { buffer: buf, count: verts.length / 9 };
      }
    }

    setSlim(slim) {
      if (this.slim !== !!slim) {
        this.slim = !!slim;
        this._rebuildPlayerGeometry();
      }
    }

    setShowOuterSkin(show) {
      if (this.showOuterSkin !== !!show) {
        this.showOuterSkin = !!show;
        this._rebuildPlayerGeometry();
      }
    }

    setSkinCanvas(canvas) {
      if (!this.gl) return;
      if (this.playerTex) this.gl.deleteTexture(this.playerTex);
      this.playerTex = this._uploadTexture(canvas);
    }

    setViewPreset(preset) {
      if (preset === "front") {
        this.yaw = 0.0; this.pitch = 0.12; this.dist = 56; this.targetY = 17;
      } else if (preset === "back") {
        this.yaw = Math.PI; this.pitch = 0.14; this.dist = 56; this.targetY = 17;
      } else if (preset === "left") {
        this.yaw = -Math.PI * 0.5; this.pitch = 0.12; this.dist = 56; this.targetY = 17;
      } else if (preset === "right" || preset === "side") {
        this.yaw = Math.PI * 0.5; this.pitch = 0.12; this.dist = 56; this.targetY = 17;
      } else if (preset === "head") {
        this.yaw = -0.35; this.pitch = 0.1; this.dist = 26; this.targetY = 27.5;
      } else if (preset === "torso") {
        this.yaw = -0.45; this.pitch = 0.12; this.dist = 34; this.targetY = 18;
      } else if (preset === "feet") {
        this.yaw = -0.4; this.pitch = 0.16; this.dist = 32; this.targetY = 7;
      } else {
        this.yaw = -0.52; this.pitch = 0.18; this.dist = 56; this.targetY = 17;
      }
    }

    setPoseMode(mode) {
      this.poseMode = mode || "idle";
    }

    setLightMode(mode) {
      this.lightMode = mode || "studio";
    }

    /**
     * Load an array of BBModel preview layers:
     *   [{ bbmodel, boneMap, offset: [dx,dy,dz], scale, colorRgb: "R,G,B" | null, slotFilter: null|"HEAD"|... }]
     */
    async setBBModelLayers(layers) {
      const gl = this.gl;
      if (!gl) return;
      // Cleanup old GPU resources
      for (const item of this.bbDrawList) {
        if (item.buffer) gl.deleteBuffer(item.buffer);
        if (item.tex) gl.deleteTexture(item.tex);
      }
      this.bbDrawList = [];
      if (!layers || !layers.length) return;

      const loadImage = (src) => new Promise((resolve) => {
        if (!src) return resolve(null);
        const im = new Image();
        im.onload = () => resolve(im);
        im.onerror = () => resolve(null);
        im.src = src;
      });

      const SLOT_BONES = {
        HEAD: ["head"],
        CHEST: ["body", "right_arm", "left_arm"],
        LEGS: ["waist", "right_leg", "left_leg"],
        FEET: ["right_foot", "left_foot"],
      };

      for (const layer of layers) {
        const bb = layer.bbmodel;
        if (!bb || !Array.isArray(bb.elements)) continue;
        const textures = bb.textures || [];
        const loadedImgs = await Promise.all(textures.map(t => loadImage(t.source)));

        // Map tint (_t) & emissive (_e) companion images
        const findCompanion = (baseName, suffix) => {
          const stem = (baseName || "").toLowerCase().replace(/\.png$/, "");
          for (let i = 0; i < textures.length; i++) {
            const ts = (textures[i].name || "").toLowerCase().replace(/\.png$/, "");
            if (ts === stem + suffix) return loadedImgs[i];
          }
          for (let i = 0; i < textures.length; i++) {
            const ts = (textures[i].name || "").toLowerCase().replace(/\.png$/, "");
            if (ts.endsWith(suffix) && !(suffix === "_e" && ts.endsWith("_t_e"))) return loadedImgs[i];
          }
          return null;
        };

        // Parse optional tint RGB
        let tintRGB = null;
        if (layer.colorRgb) {
          const parts = String(layer.colorRgb).split(",").map(v => parseInt(v.trim(), 10));
          if (parts.length === 3 && parts.every(n => !isNaN(n))) tintRGB = parts;
        }

        // Pre-bake GPU textures per texture index (applying _t tint if set!)
        const gpuTextures = [];
        const gpuEmissive = [];
        for (let i = 0; i < textures.length; i++) {
          const img = loadedImgs[i];
          if (!img) { gpuTextures.push(null); gpuEmissive.push(null); continue; }
          const c = document.createElement("canvas");
          c.width = img.width; c.height = img.height;
          const ctx = c.getContext("2d");
          ctx.drawImage(img, 0, 0);

          const tintImg = findCompanion(textures[i].name, "_t");
          if (tintImg && tintRGB) {
            const tc = document.createElement("canvas");
            tc.width = c.width; tc.height = c.height;
            const tctx = tc.getContext("2d");
            tctx.drawImage(tintImg, 0, 0, c.width, c.height);
            const mainData = ctx.getImageData(0, 0, c.width, c.height);
            const tintData = tctx.getImageData(0, 0, c.width, c.height);
            const md = mainData.data, td = tintData.data;
            for (let p = 0; p < md.length; p += 4) {
              if (td[p + 3] > 10 && md[p + 3] > 0) {
                md[p]     = (md[p]     * tintRGB[0]) >> 8;
                md[p + 1] = (md[p + 1] * tintRGB[1]) >> 8;
                md[p + 2] = (md[p + 2] * tintRGB[2]) >> 8;
              }
            }
            ctx.putImageData(mainData, 0, 0);
          }
          gpuTextures.push(this._uploadTexture(c));

          const emisImg = findCompanion(textures[i].name, "_e");
          gpuEmissive.push(emisImg ? this._uploadTexture(emisImg) : null);
        }

        // Build cube -> original group lookup
        const cubeToGroup = {};
        const walkOutliner = (node, topName) => {
          if (typeof node === "string") {
            cubeToGroup[node] = topName || "(root)";
          } else if (node && typeof node === "object") {
            const nm = topName || node.name || "group";
            (node.children || []).forEach(ch => walkOutliner(ch, nm));
          }
        };
        (bb.outliner || []).forEach(n => walkOutliner(n, null));

        const boneMap = layer.boneMap || {};
        const off = layer.offset || [0, 0, 0];
        const sc = Number(layer.scale || 1.0) || 1.0;
        const allowed = layer.slotFilter && SLOT_BONES[layer.slotFilter] ? new Set(SLOT_BONES[layer.slotFilter]) : null;
        const stripKws = (layer.stripKeywords || []).map(k => k.toLowerCase()).filter(Boolean);
        const excludeUuids = new Set(layer.excludeCubeUuids || []);

        const resW = (bb.resolution && bb.resolution.width) || 64;
        const resH = (bb.resolution && bb.resolution.height) || 64;

        // Group vertices by (bone, texIndex)
        const buckets = new Map();
        for (const el of bb.elements) {
          if (!el || !el.from || !el.to) continue;
          if (excludeUuids.has(el.uuid)) continue;
          const elNameLow = (el.name || "").toLowerCase();
          if (stripKws.some(kw => elNameLow.includes(kw))) continue;

          const origGroup = cubeToGroup[el.uuid] || "(root)";
          let bone = boneMap[origGroup] || (BONE_PIVOTS[origGroup] ? origGroup : "body");
          if (!BONE_PIVOTS[bone]) bone = "body";
          if (allowed && !allowed.has(bone)) continue;

          const pv = BONE_PIVOTS[bone];
          const xf = (p) => [
            pv[0] + (p[0] - pv[0]) * sc + off[0],
            pv[1] + (p[1] - pv[1]) * sc + off[1],
            pv[2] + (p[2] - pv[2]) * sc + off[2],
          ];
          const fr = xf(el.from);
          const to = xf(el.to);
          const orig = xf(el.origin || pv);

          // Split faces by texture index
          const facesByTex = new Map();
          for (const [dir, face] of Object.entries(el.faces || {})) {
            if (!face || !face.uv) continue;
            let tidx = typeof face.texture === "number" ? face.texture : 0;
            if (tidx < 0 || tidx >= gpuTextures.length) tidx = 0;
            if (!facesByTex.has(tidx)) facesByTex.set(tidx, {});
            facesByTex.get(tidx)[dir] = face;
          }

          for (const [tidx, fobj] of facesByTex.entries()) {
            const key = `${bone}::${tidx}`;
            if (!buckets.has(key)) buckets.set(key, { bone, tidx, verts: [], emisVerts: [] });
            const b = buckets.get(key);
            appendBoxGeometry(b.verts, fr, to, fobj, resW, resH, orig, el.rotation, (el.inflate || 0) * sc, false);
            if (gpuEmissive[tidx]) {
              // Slight 0.03 inflate so emissive glow sits cleanly over base face without z-fighting
              appendBoxGeometry(b.emisVerts, fr, to, fobj, resW, resH, orig, el.rotation, (el.inflate || 0) * sc + 0.03, true);
            }
          }
        }

        for (const b of buckets.values()) {
          if (b.verts.length && gpuTextures[b.tidx]) {
            const buf = gl.createBuffer();
            gl.bindBuffer(gl.ARRAY_BUFFER, buf);
            gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(b.verts), gl.STATIC_DRAW);
            this.bbDrawList.push({ bone: b.bone, buffer: buf, count: b.verts.length / 9, tex: gpuTextures[b.tidx], cutoff: 0.1 });
          }
          if (b.emisVerts.length && gpuEmissive[b.tidx]) {
            const ebuf = gl.createBuffer();
            gl.bindBuffer(gl.ARRAY_BUFFER, ebuf);
            gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(b.emisVerts), gl.STATIC_DRAW);
            this.bbDrawList.push({ bone: b.bone, buffer: ebuf, count: b.emisVerts.length / 9, tex: gpuEmissive[b.tidx], cutoff: 0.25 });
          }
        }
      }
    }

    _boneMatrix(bone) {
      const mode = this.poseMode || "idle";
      const pv = BONE_PIVOTS[bone] || [0, 24, 0];
      let rx = 0, ry = 0, rz = 0;
      let tx = 0, ty = 0, tz = 0;

      if (mode === "idle") {
        // Subtle natural breathing & idle sway so character feels alive
        const breath = Math.sin(this.t * 2.1);
        if (bone === "right_arm") { rz = 0.045 + breath * 0.025; rx = breath * 0.03; }
        else if (bone === "left_arm") { rz = -(0.045 + breath * 0.025); rx = -breath * 0.03; }
        else if (bone === "head") { ry = Math.sin(this.t * 0.9) * 0.09; rx = Math.sin(this.t * 1.4) * 0.03; }
        else return mat4Identity();
      } else if (mode === "walk") {
        const swing = Math.sin(this.t * 3.8) * 0.56;
        if (bone === "right_arm") rx = -swing;
        else if (bone === "left_arm") rx = swing;
        else if (bone === "right_leg" || bone === "right_foot") rx = swing * 0.85;
        else if (bone === "left_leg" || bone === "left_foot") rx = -swing * 0.85;
        else if (bone === "head") ry = Math.sin(this.t * 1.5) * 0.14;
        else return mat4Identity();
      } else if (mode === "run") {
        const swing = Math.sin(this.t * 6.8) * 0.92;
        const bob = Math.abs(Math.sin(this.t * 6.8)) * 0.75;
        ty = bob;
        if (bone === "body" || bone === "waist") { rx = -0.14; }
        else if (bone === "head") { rx = 0.08; ry = Math.sin(this.t * 3.4) * 0.1; tz = -1.2; }
        else if (bone === "right_arm") { rx = -swing - 0.15; rz = 0.08; }
        else if (bone === "left_arm") { rx = swing - 0.15; rz = -0.08; }
        else if (bone === "right_leg" || bone === "right_foot") { rx = swing * 0.98; }
        else if (bone === "left_leg" || bone === "left_foot") { rx = -swing * 0.98; }
      } else if (mode === "sneak") {
        // Minecraft sneak crouch: torso leans forward around waist, head & arms follow cleanly
        if (bone === "body") {
          let m = mat4Translate(0, 11.2, 3.0);
          m = mat4Mul(m, mat4RotX(-0.46));
          m = mat4Mul(m, mat4Translate(0, -12, 0));
          return m;
        } else if (bone === "waist") {
          let m = mat4Translate(0, 11.2, 3.0);
          m = mat4Mul(m, mat4RotX(-0.46));
          m = mat4Mul(m, mat4Translate(0, -12, 0));
          return m;
        } else if (bone === "head") {
          ty = -3.4; tz = -2.2; rx = 0.08;
        } else if (bone === "right_arm") {
          ty = -3.0; tz = -1.8; rx = -0.35; rz = 0.08;
        } else if (bone === "left_arm") {
          ty = -3.0; tz = -1.8; rx = -0.35; rz = -0.08;
        } else if (bone === "right_leg" || bone === "right_foot") {
          tz = 3.0; ty = -0.6;
        } else if (bone === "left_leg" || bone === "left_foot") {
          tz = 3.0; ty = -0.6;
        }
      } else if (mode === "apose") {
        // Inspection A-pose: arms angled out so torso sides, waist belts & sleeves are clearly visible
        if (bone === "right_arm") rz = 0.58;
        else if (bone === "left_arm") rz = -0.58;
        else return mat4Identity();
      } else {
        return mat4Identity();
      }

      let m = mat4Translate(pv[0] + tx, pv[1] + ty, pv[2] + tz);
      if (ry) m = mat4Mul(m, mat4RotY(ry));
      if (rx) m = mat4Mul(m, mat4RotX(rx));
      if (rz) m = mat4Mul(m, mat4RotZ(rz));
      m = mat4Mul(m, mat4Translate(-pv[0], -pv[1], -pv[2]));
      return m;
    }

    _bindPointer() {
      let dragging = false, rightDrag = false, lx = 0, ly = 0;
      let pinchDist = 0;
      const c = this.canvas;
      c.addEventListener("contextmenu", e => e.preventDefault());
      c.addEventListener("mousedown", e => {
        dragging = true;
        rightDrag = e.button === 2 || e.shiftKey;
        lx = e.clientX; ly = e.clientY;
      });
      window.addEventListener("mouseup", () => { dragging = false; });
      window.addEventListener("mousemove", e => {
        if (!dragging) return;
        const dx = e.clientX - lx, dy = e.clientY - ly;
        lx = e.clientX; ly = e.clientY;
        if (rightDrag) {
          this.targetY = Math.max(4, Math.min(34, this.targetY + dy * 0.08));
        } else {
          this.yaw += dx * 0.011;
          this.pitch = Math.max(-1.2, Math.min(1.25, this.pitch + dy * 0.01));
        }
      });
      c.addEventListener("wheel", e => {
        e.preventDefault();
        this.dist = Math.max(18, Math.min(110, this.dist + e.deltaY * 0.05));
      }, { passive: false });

      // Touch drag rotate & pinch zoom
      c.addEventListener("touchstart", e => {
        if (e.touches.length === 1) {
          dragging = true;
          lx = e.touches[0].clientX;
          ly = e.touches[0].clientY;
        } else if (e.touches.length === 2) {
          dragging = false;
          pinchDist = Math.hypot(
            e.touches[0].clientX - e.touches[1].clientX,
            e.touches[0].clientY - e.touches[1].clientY
          );
        }
      }, { passive: true });
      c.addEventListener("touchmove", e => {
        if (e.touches.length === 1 && dragging) {
          const dx = e.touches[0].clientX - lx;
          const dy = e.touches[0].clientY - ly;
          lx = e.touches[0].clientX;
          ly = e.touches[0].clientY;
          this.yaw += dx * 0.012;
          this.pitch = Math.max(-1.2, Math.min(1.25, this.pitch + dy * 0.011));
        } else if (e.touches.length === 2) {
          const nd = Math.hypot(
            e.touches[0].clientX - e.touches[1].clientX,
            e.touches[0].clientY - e.touches[1].clientY
          );
          if (pinchDist > 0) {
            this.dist = Math.max(18, Math.min(110, this.dist + (pinchDist - nd) * 0.14));
          }
          pinchDist = nd;
        }
      }, { passive: true });
      c.addEventListener("touchend", () => { dragging = false; pinchDist = 0; }, { passive: true });
    }

    _startLoop() {
      let last = performance.now();
      const tick = (now) => {
        const dt = Math.min(0.05, (now - last) / 1000);
        last = now;
        this.t += dt;
        if (this.autoSpin) this.yaw += dt * 0.65;
        this.render();
        requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    }

    get showMannequin() { return this.showPlayer; }
    set showMannequin(v) { this.showPlayer = !!v; }
    get autoRotate() { return this.autoSpin; }
    set autoRotate(v) { this.autoSpin = !!v; }
    get walkAnim() { return this.poseMode === "walk"; }
    set walkAnim(v) { this.poseMode = v ? "walk" : "idle"; }

    resize() { this.render(); }
    setViewFront() { this.setViewPreset("front"); }
    setViewBack() { this.setViewPreset("back"); }
    resetView() { this.setViewPreset("default"); }

    /**
     * Captures a clean transparent PNG screenshot of the full combined 2D + 3D character!
     */
    captureFullCharacterPng(transparent = true) {
      const gl = this.gl;
      if (!gl) return null;
      const prevGrid = this.showGrid;
      const prevBones = this.showBones;
      this.showGrid = false;
      this.showBones = false;
      this._transparentBg = Boolean(transparent);
      this.render();
      const url = this.canvas.toDataURL("image/png");
      this._transparentBg = false;
      this.showGrid = prevGrid;
      this.showBones = prevBones;
      this.render();
      return url;
    }

    setSkinDataUri(dataUri, slim) {
      if (typeof slim === "boolean") this.setSlim(slim);
      if (!dataUri) return;
      const im = new Image();
      im.onload = () => {
        const c = document.createElement("canvas");
        c.width = 64; c.height = 64;
        const ctx = c.getContext("2d");
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(im, 0, 0, 64, 64);
        this.setSkinCanvas(c);
      };
      im.src = dataUri;
    }

    async setModels(layers) {
      return this.setBBModelLayers(layers);
    }

    /**
     * Captures a transparent cropped pixel-crisp 2D inventory icon PNG from the current 3D armor model!
     */
    captureIconDataUri(size = 32) {
      const gl = this.gl;
      if (!gl || !this.bbDrawList.length) return null;
      const prevPlayer = this.showPlayer;
      const prevGrid = this.showGrid;
      const prevBones = this.showBones;
      const prevArmor = this.showArmor;
      this.showPlayer = false;
      this.showGrid = false;
      this.showBones = false;
      this.showArmor = true;
      this._transparentBg = true;
      this.render();
      this._transparentBg = false;
      this.showPlayer = prevPlayer;
      this.showGrid = prevGrid;
      this.showBones = prevBones;
      this.showArmor = prevArmor;

      // Copy WebGL framebuffer into 2D canvas and auto-crop to tight bounding box
      const srcW = this.canvas.width, srcH = this.canvas.height;
      const tmp = document.createElement("canvas");
      tmp.width = srcW; tmp.height = srcH;
      const tctx = tmp.getContext("2d");
      tctx.drawImage(this.canvas, 0, 0);
      const data = tctx.getImageData(0, 0, srcW, srcH).data;
      let minX = srcW, minY = srcH, maxX = -1, maxY = -1;
      for (let y = 0; y < srcH; y++) {
        for (let x = 0; x < srcW; x++) {
          if (data[(y * srcW + x) * 4 + 3] > 20) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
          }
        }
      }
      // Restore normal rendered frame
      this.render();
      if (maxX < minX || maxY < minY) return null;

      const bw = maxX - minX + 1;
      const bh = maxY - minY + 1;
      const pad = 2;
      const inner = size - pad * 2;
      const scale = Math.min(inner / bw, inner / bh);
      const dw = Math.max(1, Math.round(bw * scale));
      const dh = Math.max(1, Math.round(bh * scale));
      const dx = Math.floor((size - dw) / 2);
      const dy = Math.floor((size - dh) / 2);

      const out = document.createElement("canvas");
      out.width = size; out.height = size;
      const octx = out.getContext("2d");
      octx.imageSmoothingEnabled = false;
      octx.drawImage(tmp, minX, minY, bw, bh, dx, dy, dw, dh);
      return out.toDataURL("image/png");
    }

    render() {
      const gl = this.gl;
      if (!gl) return;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = Math.max(1, Math.floor(this.canvas.clientWidth * dpr));
      const h = Math.max(1, Math.floor(this.canvas.clientHeight * dpr));
      if (this.canvas.width !== w || this.canvas.height !== h) {
        this.canvas.width = w;
        this.canvas.height = h;
      }
      gl.viewport(0, 0, w, h);
      const lm = this.lightMode || "studio";
      if (this._transparentBg) {
        gl.clearColor(0.0, 0.0, 0.0, 0.0);
      } else if (lm === "day") {
        gl.clearColor(0.095, 0.100, 0.110, 1.0);
      } else if (lm === "night") {
        gl.clearColor(0.012, 0.013, 0.017, 1.0);
      } else {
        gl.clearColor(0.032, 0.032, 0.034, 1.0);
      }
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);

      const proj = mat4Perspective(38 * DEG, w / h, 0.5, 400);
      // Camera looks at Front (-Z) when yaw = 0!
      const cx = Math.sin(this.yaw) * Math.cos(this.pitch) * this.dist;
      const cy = this.targetY + Math.sin(this.pitch) * this.dist;
      const cz = -Math.cos(this.yaw) * Math.cos(this.pitch) * this.dist;
      const view = mat4LookAt([cx, cy, cz], [0, this.targetY, 0], [0, 1, 0]);

      // 1. Floor grid
      if (this.showGrid) {
        gl.useProgram(this.lineProg);
        gl.uniformMatrix4fv(this.lineLoc.uProj, false, proj);
        gl.uniformMatrix4fv(this.lineLoc.uView, false, view);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.gridBuffer);
        gl.enableVertexAttribArray(this.lineLoc.aPos);
        gl.vertexAttribPointer(this.lineLoc.aPos, 3, gl.FLOAT, false, 24, 0);
        gl.enableVertexAttribArray(this.lineLoc.aCol);
        gl.vertexAttribPointer(this.lineLoc.aCol, 3, gl.FLOAT, false, 24, 12);
        gl.drawArrays(gl.LINES, 0, this.gridCount);
      }

      // 2. Textured meshes
      gl.useProgram(this.prog);
      gl.uniformMatrix4fv(this.loc.uProj, false, proj);
      gl.uniformMatrix4fv(this.loc.uView, false, view);
      gl.activeTexture(gl.TEXTURE0);
      gl.uniform1i(this.loc.uTex, 0);
      if (lm === "day") {
        gl.uniform1f(this.loc.uAmbient, 0.72);
        gl.uniform1f(this.loc.uDiffuse, 0.44);
        gl.uniform1f(this.loc.uEmissiveBoost, 1.12);
      } else if (lm === "night") {
        gl.uniform1f(this.loc.uAmbient, 0.22);
        gl.uniform1f(this.loc.uDiffuse, 0.24);
        gl.uniform1f(this.loc.uEmissiveBoost, 1.38);
      } else {
        gl.uniform1f(this.loc.uAmbient, 0.56);
        gl.uniform1f(this.loc.uDiffuse, 0.44);
        gl.uniform1f(this.loc.uEmissiveBoost, 1.16);
      }

      const drawMesh = (buf, count, tex, bone, cutoff) => {
        gl.uniformMatrix4fv(this.loc.uModel, false, this._boneMatrix(bone));
        gl.uniform1f(this.loc.uAlphaCutoff, cutoff);
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.bindBuffer(gl.ARRAY_BUFFER, buf);
        gl.enableVertexAttribArray(this.loc.aPos);
        gl.vertexAttribPointer(this.loc.aPos, 3, gl.FLOAT, false, 36, 0);
        gl.enableVertexAttribArray(this.loc.aNorm);
        gl.vertexAttribPointer(this.loc.aNorm, 3, gl.FLOAT, false, 36, 12);
        gl.enableVertexAttribArray(this.loc.aUv);
        gl.vertexAttribPointer(this.loc.aUv, 2, gl.FLOAT, false, 36, 24);
        gl.enableVertexAttribArray(this.loc.aEmis);
        gl.vertexAttribPointer(this.loc.aEmis, 1, gl.FLOAT, false, 36, 32);
        gl.drawArrays(gl.TRIANGLES, 0, count);
      };

      if (this.showPlayer && this.playerTex) {
        for (const [bone, mesh] of Object.entries(this.playerMeshes)) {
          drawMesh(mesh.buffer, mesh.count, this.playerTex, bone, 0.1);
        }
      }

      if (this.showArmor) {
        for (const item of this.bbDrawList) {
          drawMesh(item.buffer, item.count, item.tex, item.bone, item.cutoff);
        }
      }

      // 3. Optional bone pivot gizmos (drawn on top without depth test)
      if (this.showBones) {
        gl.disable(gl.DEPTH_TEST);
        gl.useProgram(this.lineProg);
        gl.uniformMatrix4fv(this.lineLoc.uProj, false, proj);
        gl.uniformMatrix4fv(this.lineLoc.uView, false, view);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.boneGizmoBuffer);
        gl.enableVertexAttribArray(this.lineLoc.aPos);
        gl.vertexAttribPointer(this.lineLoc.aPos, 3, gl.FLOAT, false, 24, 0);
        gl.enableVertexAttribArray(this.lineLoc.aCol);
        gl.vertexAttribPointer(this.lineLoc.aCol, 3, gl.FLOAT, false, 24, 12);
        gl.drawArrays(gl.LINES, 0, this.boneGizmoCount);
        gl.enable(gl.DEPTH_TEST);
      }
    }
  }

  global.Studio3DViewer = Studio3DViewer;
  global.StudioWebGLViewer = Studio3DViewer;
})(window);
