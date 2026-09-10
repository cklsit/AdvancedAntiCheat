<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import type { TracePoint, ViolationMarker, ReplayLayers } from '@/types/replay'

// ==================== Props / Emits ====================
interface Props {
  /** 轨迹点（按 t 升序，新类型 TracePoint 含 blockHeights + mainHandItemId） */
  points: TracePoint[]
  /** 当前播放位置（相对 startTime 的毫秒偏移） */
  currentMs: number
  /** 全部违规标记列表（支持多个） */
  violations: ViolationMarker[]
  /** 视角模式 */
  perspective: 'first' | 'third' | 'free'
  /** 图层开关 */
  layers: ReplayLayers
  /** 玩家名（头部上方悬浮标签） */
  playerName?: string
}

const props = withDefaults(defineProps<Props>(), {
  playerName: ''
})

const emit = defineEmits<{
  (e: 'ready'): void
  (e: 'error'): void
}>()

// ==================== Refs ====================
const containerRef = ref<HTMLDivElement | null>(null)
const webglFailed = ref(false)

// ==================== Three.js 实例（动态 import 懒加载） ====================
type ThreeNS = typeof import('three')
type OrbitControlsModule = typeof import('three/examples/jsm/controls/OrbitControls.js')

let THREE: ThreeNS | null = null
let OrbitControlsCls: OrbitControlsModule['OrbitControls'] | null = null

let scene: import('three').Scene | null = null
let camera: import('three').PerspectiveCamera | null = null
let renderer: import('three').WebGLRenderer | null = null
let controls: import('three/examples/jsm/controls/OrbitControls.js').OrbitControls | null = null
let contentGroup: import('three').Group | null = null
let rafId = 0
let resizeObserver: ResizeObserver | null = null

// 场景对象引用
let playerAnchor: import('three').Group | null = null        // 玩家锚点（只做位移，不旋转）
let playerYawGroup: import('three').Group | null = null      // 身体（随 yaw 旋转）
let playerHeadGroup: import('three').Group | null = null     // 头部（随 pitch 旋转）
let nameSprite: import('three').Sprite | null = null
let hitboxGroup: import('three').Group | null = null
let gridHelper: import('three').GridHelper | null = null
let trailFull: import('three').Line | null = null            // 全轨迹（暗色虚线，未走过）
let trailPassed: import('three').Line | null = null          // 已走过（彩色实线）
let violationGroup: import('three').Group | null = null      // 所有违规标记的父组
let terrainGroup: import('three').Group | null = null        // 地形高度图父组

// 地形材质缓存（复用避免频繁创建）
let terrainBrownMat: import('three').MeshLambertMaterial | null = null
let terrainGreenMat: import('three').MeshLambertMaterial | null = null
let terrainWhiteMat: import('three').MeshLambertMaterial | null = null

// 轨迹包围盒（用于场景居中 / 地面高度 / free 视角目标）
const bounds = { cx: 0, cy: 0, cz: 0, minY: 0, maxY: 64, radius: 20 }

// 第三人称相机平滑：临时目标向量
let tmpTarget: import('three').Vector3 | null = null
let snapCamera = true

// 上一次地形更新的帧索引（避免每帧重建所有地形 mesh）
let lastTerrainIdx = -1

// ==================== WebGL 检测 ====================
function detectWebGL(): boolean {
  try {
    const c = document.createElement('canvas')
    return !!(c.getContext('webgl2') || c.getContext('webgl'))
  } catch {
    return false
  }
}

// ==================== 初始化 ====================
async function initThree(): Promise<void> {
  const container = containerRef.value
  if (!container) return

  try {
    const [threeMod, controlsMod] = await Promise.all([
      import('three'),
      import('three/examples/jsm/controls/OrbitControls.js')
    ])
    THREE = threeMod as unknown as ThreeNS
    OrbitControlsCls = controlsMod.OrbitControls
  } catch {
    webglFailed.value = true
    emit('error')
    return
  }

  if (!THREE || !containerRef.value) return
  if (!detectWebGL()) {
    webglFailed.value = true
    emit('error')
    return
  }

  try {
    renderer = new THREE.WebGLRenderer({ antialias: true })
  } catch {
    webglFailed.value = true
    emit('error')
    return
  }

  const w = Math.max(1, container.clientWidth)
  const h = Math.max(1, container.clientHeight)

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0a0e14)

  camera = new THREE.PerspectiveCamera(60, w / h, 0.1, 800)
  camera.position.set(20, 25, 20)
  camera.lookAt(0, 0, 0)

  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(w, h, false)
  container.appendChild(renderer.domElement)

  // 光照
  const ambient = new THREE.AmbientLight(0xffffff, 0.75)
  scene.add(ambient)
  const dir = new THREE.DirectionalLight(0xdfefff, 0.7)
  dir.position.set(40, 80, 25)
  scene.add(dir)

  // OrbitControls（仅 free 视角启用）
  if (OrbitControlsCls && camera && renderer) {
    controls = new OrbitControlsCls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.dampingFactor = 0.08
    controls.enabled = false
  }

  tmpTarget = new THREE.Vector3()

  // 地形材质（提前创建好）
  terrainBrownMat = new THREE.MeshLambertMaterial({ color: 0x8b5a2b })
  terrainGreenMat = new THREE.MeshLambertMaterial({ color: 0x4a9e4a })
  terrainWhiteMat = new THREE.MeshLambertMaterial({ color: 0xeaeaea })

  // 场景内容（轨迹 / 玩家 / 标记）
  buildScene()
  applyPerspective(props.perspective, true)
  startLoop()

  // ResizeObserver
  resizeObserver = new ResizeObserver(() => onResize())
  resizeObserver.observe(container)

  emit('ready')
}

// ==================== 场景构建（轨迹变化时重建） ====================
function buildScene(): void {
  if (!THREE || !scene) return
  // 清理旧内容
  if (contentGroup) {
    disposeObject(contentGroup)
    scene.remove(contentGroup)
  }
  contentGroup = new THREE.Group()
  scene.add(contentGroup)

  const pts = props.points
  if (pts.length === 0) return

  // ---- 1. 轨迹包围盒 ----
  let minX = Infinity, maxX = -Infinity
  let minY = Infinity, maxY = -Infinity
  let minZ = Infinity, maxZ = -Infinity
  for (const p of pts) {
    if (p.x < minX) minX = p.x
    if (p.x > maxX) maxX = p.x
    if (p.y < minY) minY = p.y
    if (p.y > maxY) maxY = p.y
    if (p.z < minZ) minZ = p.z
    if (p.z > maxZ) maxZ = p.z
  }
  const dx = Math.max(1, maxX - minX)
  const dz = Math.max(1, maxZ - minZ)
  const dy = Math.max(1, maxY - minY)
  bounds.cx = (minX + maxX) / 2
  bounds.cy = (minY + maxY) / 2
  bounds.cz = (minZ + maxZ) / 2
  bounds.minY = minY
  bounds.maxY = maxY
  bounds.radius = Math.max(16, Math.sqrt(dx * dx + dz * dz + dy * dy) / 2)

  // 雾效随场景尺度自适应
  scene.fog = new THREE.Fog(0x0a0e14, bounds.radius * 6, bounds.radius * 18)

  // ---- 2. 网格地面 ----
  const gridSize = Math.max(32, Math.ceil(Math.max(dx, dz) * 1.6))
  gridHelper = new THREE.GridHelper(gridSize, Math.ceil(gridSize / 2), 0x2d3949, 0x1a2230)
  const gridMat = gridHelper.material as import('three').Material
  gridMat.transparent = true
  gridMat.opacity = 0.35
  gridHelper.position.set(bounds.cx, bounds.minY, bounds.cz)
  gridHelper.visible = props.layers.grid
  contentGroup.add(gridHelper)

  // ---- 3. 轨迹线（支持多个违规时刻偏红） ----
  buildTrailLines(pts)

  // ---- 4. 所有违规时刻标记 ----
  buildViolationMarks()

  // ---- 5. 玩家小人 + 名牌 + 命中盒 ----
  buildPlayer()

  // ---- 6. 地形高度图容器（每帧更新，初始为空） ----
  terrainGroup = new THREE.Group()
  terrainGroup.visible = props.layers.terrain
  contentGroup.add(terrainGroup)

  snapCamera = true
  lastTerrainIdx = -1
}

/** 双轨迹线：已走过彩色实线（vertexColors 渐变）+ 全程暗色虚线 */
function buildTrailLines(pts: TracePoint[]): void {
  if (!THREE || !contentGroup || pts.length < 2) return

  const positions = new Float32Array(pts.length * 3)
  const colors = new Float32Array(pts.length * 3)

  const C_BLUE = new THREE.Color(0x1f6feb)
  const C_CYAN = new THREE.Color(0x00e5ff)
  const C_YELLOW = new THREE.Color(0xffd43b)
  const C_RED = new THREE.Color(0xf85149)
  const c = new THREE.Color()

  const t0 = pts[0].t
  const t1 = pts[pts.length - 1].t
  const span = Math.max(1, t1 - t0)

  // 所有违规时刻 t 集合
  const vioTs = props.violations.map(v => v.t)

  for (let i = 0; i < pts.length; i++) {
    positions[i * 3] = pts[i].x
    positions[i * 3 + 1] = pts[i].y
    positions[i * 3 + 2] = pts[i].z

    const ratio = (pts[i].t - t0) / span
    if (ratio < 1 / 3) {
      c.copy(C_BLUE).lerp(C_CYAN, ratio * 3)
    } else if (ratio < 2 / 3) {
      c.copy(C_CYAN).lerp(C_YELLOW, (ratio - 1 / 3) * 3)
    } else {
      c.copy(C_YELLOW).lerp(C_RED, (ratio - 2 / 3) * 3)
    }
    // 违规时刻附近偏红（1.5s 窗口）
    for (const vt of vioTs) {
      const dv = Math.abs(pts[i].t - vt)
      if (dv < 1500) {
        c.lerp(C_RED, (1 - dv / 1500) * 0.85)
      }
    }
    colors[i * 3] = c.r
    colors[i * 3 + 1] = c.g
    colors[i * 3 + 2] = c.b
  }

  const fullGeo = new THREE.BufferGeometry()
  fullGeo.setAttribute('position', new THREE.BufferAttribute(positions.slice(), 3))
  trailFull = new THREE.Line(fullGeo, new THREE.LineDashedMaterial({
    color: 0x46586e,
    dashSize: 0.45,
    gapSize: 0.3,
    transparent: true,
    opacity: 0.55
  }))
  trailFull.computeLineDistances()
  trailFull.visible = props.layers.trail
  contentGroup.add(trailFull)

  const passedGeo = new THREE.BufferGeometry()
  passedGeo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  passedGeo.setAttribute('color', new THREE.BufferAttribute(colors, 3))
  trailPassed = new THREE.Line(passedGeo, new THREE.LineBasicMaterial({
    vertexColors: true,
    transparent: true,
    opacity: 0.95
  }))
  trailPassed.visible = props.layers.trail
  contentGroup.add(trailPassed)
}

/** 所有违规时刻标记：红色球 + 光柱 + 光圈 */
function buildViolationMarks(): void {
  if (!THREE || !contentGroup) return
  if (violationGroup) {
    disposeObject(violationGroup)
    contentGroup.remove(violationGroup)
  }
  violationGroup = new THREE.Group()

  for (const v of props.violations) {
    const viIdx = findIndexAt(v.t, props.points)
    if (viIdx < 0) continue
    const p = props.points[viIdx]

    const g = new THREE.Group()

    // 红色球
    const core = new THREE.Mesh(
      new THREE.SphereGeometry(0.3, 24, 16),
      new THREE.MeshBasicMaterial({ color: 0xf85149 })
    )
    core.position.set(p.x, p.y, p.z)
    g.add(core)

    // 光圈
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(0.6, 1.1, 48),
      new THREE.MeshBasicMaterial({
        color: 0xf85149,
        transparent: true,
        opacity: 0.35,
        side: THREE.DoubleSide,
        depthWrite: false
      })
    )
    ring.rotation.x = -Math.PI / 2
    ring.position.set(p.x, bounds.minY + 0.05, p.z)
    g.add(ring)

    // 光柱
    const pillarH = Math.max(1, p.y - bounds.minY)
    const pillar = new THREE.Mesh(
      new THREE.CylinderGeometry(0.28, 0.28, pillarH, 16, 1, true),
      new THREE.MeshBasicMaterial({
        color: 0xf85149,
        transparent: true,
        opacity: 0.1,
        side: THREE.DoubleSide,
        depthWrite: false
      })
    )
    pillar.position.set(p.x, bounds.minY + pillarH / 2, p.z)
    g.add(pillar)

    violationGroup.add(g)
  }

  violationGroup.visible = props.layers.violation
  contentGroup.add(violationGroup)
}

/** 玩家小人：浅蓝 Box 人形 + 眼睛 + 名牌 Sprite + 命中盒 */
function buildPlayer(): void {
  if (!THREE || !contentGroup) return

  playerAnchor = new THREE.Group()

  const yawGroup = new THREE.Group()
  const bodyMat = new THREE.MeshLambertMaterial({ color: 0x69b8ff })
  const darkMat = new THREE.MeshLambertMaterial({ color: 0x4a86c8 })

  // 身体 0.6 × 1.2 × 0.3（y 0.3 ~ 1.5）
  const body = new THREE.Mesh(new THREE.BoxGeometry(0.6, 1.2, 0.3), bodyMat)
  body.position.y = 0.9
  yawGroup.add(body)

  // 头部组（随 pitch 旋转）：0.5³（y 1.5 ~ 2.0）
  const headGroup = new THREE.Group()
  headGroup.position.y = 1.75
  const head = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.5, 0.5), bodyMat)
  headGroup.add(head)
  const eyeMat = new THREE.MeshBasicMaterial({ color: 0x10202e })
  const eyeL = new THREE.Mesh(new THREE.BoxGeometry(0.09, 0.09, 0.03), eyeMat)
  eyeL.position.set(-0.12, 0.02, 0.26)
  const eyeR = eyeL.clone()
  eyeR.position.x = 0.12
  headGroup.add(eyeL, eyeR)
  yawGroup.add(headGroup)

  // 四肢
  const armGeo = new THREE.BoxGeometry(0.2, 0.7, 0.2)
  const armL = new THREE.Mesh(armGeo, darkMat)
  armL.position.set(-0.42, 1.05, 0)
  const armR = armL.clone()
  armR.position.x = 0.42
  yawGroup.add(armL, armR)

  const legGeo = new THREE.BoxGeometry(0.22, 0.6, 0.22)
  const legL = new THREE.Mesh(legGeo, darkMat)
  legL.position.set(-0.15, 0.3, 0)
  const legR = legL.clone()
  legR.position.x = 0.15
  yawGroup.add(legL, legR)

  playerYawGroup = yawGroup
  playerHeadGroup = headGroup
  playerAnchor.add(yawGroup)

  // 名牌 Sprite
  if (props.playerName) {
    nameSprite = makeNameSprite(props.playerName)
    nameSprite.position.y = 2.35
    playerAnchor.add(nameSprite)
  }

  // 命中盒
  const hitG = new THREE.Group()
  const hitMat = new THREE.MeshBasicMaterial({
    color: 0xf85149,
    transparent: true,
    opacity: 0.12,
    depthWrite: false
  })
  const hitBox = new THREE.Mesh(new THREE.BoxGeometry(0.6, 1.8, 0.6), hitMat)
  hitG.add(hitBox)
  const hitEdges = new THREE.LineSegments(
    new THREE.EdgesGeometry(new THREE.BoxGeometry(0.6, 1.8, 0.6)),
    new THREE.LineBasicMaterial({ color: 0xf85149, transparent: true, opacity: 0.55 })
  )
  hitG.add(hitEdges)
  hitG.position.y = 0.9
  hitG.visible = props.layers.hitbox
  hitboxGroup = hitG
  playerAnchor.add(hitG)

  contentGroup.add(playerAnchor)
}

/** Canvas 生成玩家名文字贴图 Sprite */
function makeNameSprite(name: string): import('three').Sprite {
  const canvas = document.createElement('canvas')
  canvas.width = 320
  canvas.height = 72
  const ctx = canvas.getContext('2d')!

  ctx.fillStyle = 'rgba(8, 14, 22, 0.72)'
  const r = 12
  ctx.beginPath()
  ctx.roundRect(4, 8, canvas.width - 8, 56, r)
  ctx.fill()
  ctx.strokeStyle = 'rgba(0, 229, 255, 0.45)'
  ctx.lineWidth = 2
  ctx.stroke()

  ctx.font = '600 30px "JetBrains Mono", "Segoe UI", sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#7fdcff'
  ctx.fillText(name, canvas.width / 2, 36)

  const texture = new THREE!.CanvasTexture(canvas)
  const sprite = new THREE!.Sprite(new THREE!.SpriteMaterial({
    map: texture,
    transparent: true,
    depthTest: false
  }))
  sprite.scale.set(2.6, 0.58, 1)
  sprite.renderOrder = 10
  return sprite
}

// ==================== 地形高度图渲染 ====================
/**
 * 简化方案：只渲染当前帧 + 前后各 2 帧（共 5 帧）的 blockHeights。
 * 每帧的 blockHeights 是 7×7 网格，每个格一个 0.9×h×0.9 的 Box。
 * blockHeights 是相对 y=0 的方块堆叠高度（Minecraft 世界坐标）。
 */
function updateTerrain(centerIdx: number): void {
  if (!THREE || !terrainGroup || !terrainBrownMat || !terrainGreenMat || !terrainWhiteMat) return
  const pts = props.points
  if (centerIdx < 0 || centerIdx >= pts.length) return

  // 视野裁剪窗口：idx-2 ~ idx+2
  const windowStart = Math.max(0, centerIdx - 2)
  const windowEnd = Math.min(pts.length - 1, centerIdx + 2)

  // 清空 terrainGroup（释放旧 geometry）
  while (terrainGroup.children.length) {
    const child = terrainGroup.children.pop()!
    disposeObject(child)
  }

  const boxGeo = new THREE.BoxGeometry(0.9, 1, 0.9)
  // 将 geometry 的中心对齐到底面（让 y=0 就是地面）
  boxGeo.translate(0, 0.5, 0)

  for (let fi = windowStart; fi <= windowEnd; fi++) {
    const p = pts[fi]
    const bh = p.blockHeights
    if (!bh || bh.length < 49) continue

    // 中心方块索引（7×7 的第 4 行第 4 列 = index 24）对应玩家脚下
    // 玩家坐标为 (p.x, p.y, p.z)，把 7×7 网格以玩家为中心摆放
    // MC 方块尺寸 1，网格从 offset -3 ~ +3
    for (let gz = -3; gz <= 3; gz++) {
      for (let gx = -3; gx <= 3; gx++) {
        const idx = (gz + 3) * 7 + (gx + 3)
        const h = bh[idx]
        if (h <= 0) continue

        // 根据高度选材质（低棕、中绿、高白）
        let mat = terrainGreenMat
        const norm = Math.max(0, Math.min(1, h / 64))
        if (norm < 0.25) mat = terrainBrownMat
        else if (norm > 0.7) mat = terrainWhiteMat

        const mesh = new THREE.Mesh(boxGeo, mat)
        // 方块中心坐标（玩家 xz + 网格偏移），y 从 0 堆叠 h 格
        mesh.position.set(
          p.x + gx,
          0,
          p.z + gz
        )
        mesh.scale.set(1, h, 1)
        mesh.visible = props.layers.terrain
        terrainGroup.add(mesh)
      }
    }
  }

  // 释放 boxGeo（每个窗口只建一次）
  boxGeo.dispose()
}

// ==================== 二分查找 ====================
function findIndexAt(ms: number, pts: TracePoint[]): number {
  if (!pts.length) return -1
  if (ms <= pts[0].t) return 0
  if (ms >= pts[pts.length - 1].t) return pts.length - 1
  let lo = 0
  let hi = pts.length - 1
  let ans = 0
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1
    if (pts[mid].t <= ms) { ans = mid; lo = mid + 1 }
    else { hi = mid - 1 }
  }
  return ans
}

// ==================== 每帧更新 ====================
function updatePlayer(idx: number): void {
  const pts = props.points
  if (!playerAnchor || idx < 0 || idx >= pts.length) return
  const p = pts[idx]

  playerAnchor.position.set(p.x, p.y, p.z)

  // Minecraft yaw → Three.js：yaw 0 = +Z（南），顺时针为正
  if (playerYawGroup) {
    playerYawGroup.rotation.y = (-p.yaw * Math.PI) / 180
  }
  if (playerHeadGroup) {
    playerHeadGroup.rotation.x = (p.pitch * Math.PI) / 180
  }

  trailPassed?.geometry.setDrawRange(0, idx + 1)

  // 地形更新（每帧都可能变化，但用窗口裁剪避免全部重建）
  if (idx !== lastTerrainIdx) {
    updateTerrain(idx)
    lastTerrainIdx = idx
  }
}

function updateCamera(idx: number): void {
  if (!camera) return
  const pts = props.points
  if (idx < 0 || idx >= pts.length) return
  const p = pts[idx]

  if (props.perspective === 'free') {
    controls?.update()
    return
  }

  const yawRad = (p.yaw * Math.PI) / 180
  const pitchRad = (p.pitch * Math.PI) / 180

  if (props.perspective === 'first') {
    camera.position.set(p.x, p.y + 1.62, p.z)
    camera.rotation.order = 'YXZ'
    camera.rotation.set(-pitchRad, -yawRad + Math.PI, 0)
    return
  }

  // 第三人称
  const forward = { x: -Math.sin(yawRad), z: Math.cos(yawRad) }
  if (tmpTarget) {
    tmpTarget.set(p.x - forward.x * 8, p.y + 5, p.z - forward.z * 8)
    if (snapCamera) {
      camera.position.copy(tmpTarget)
      snapCamera = false
    } else {
      camera.position.lerp(tmpTarget, 0.15)
    }
  }
  camera.lookAt(p.x, p.y + 1.6, p.z)
}

// ==================== 渲染循环 ====================
function startLoop(): void {
  const tick = (): void => {
    rafId = requestAnimationFrame(tick)
    if (!renderer || !scene || !camera) return
    const idx = findIndexAt(props.currentMs, props.points)
    if (idx >= 0) {
      updatePlayer(idx)
      updateCamera(idx)
    }
    renderer.render(scene, camera)
  }
  rafId = requestAnimationFrame(tick)
}

// ==================== 视角切换 ====================
function applyPerspective(p: Props['perspective'], snap = false): void {
  if (!camera || !controls) return
  if (p === 'free') {
    controls.enabled = true
    controls.target.set(bounds.cx, bounds.cy, bounds.cz)
    if (snap || snapCamera) {
      const d = bounds.radius * 1.8
      camera.position.set(bounds.cx + d * 0.7, bounds.cy + d * 0.6, bounds.cz + d * 0.7)
      snapCamera = false
    }
    controls.update()
  } else {
    controls.enabled = false
    if (p === 'third') snapCamera = true
  }
}

watch(() => props.perspective, (p) => { applyPerspective(p) })

// ==================== 图层开关 ====================
watch(() => props.layers, () => {
  if (gridHelper) gridHelper.visible = props.layers.grid
  if (trailFull) trailFull.visible = props.layers.trail
  if (trailPassed) trailPassed.visible = props.layers.trail
  if (violationGroup) violationGroup.visible = props.layers.violation
  if (hitboxGroup) hitboxGroup.visible = props.layers.hitbox
  if (terrainGroup) terrainGroup.visible = props.layers.terrain
  // 如果 terrain 隐藏，强制让下一个 updatePlayer 重建地形（显隐切换后）
  lastTerrainIdx = -1
}, { deep: true })

// ==================== 轨迹变化：重建场景 ====================
watch(() => props.points, () => {
  if (!scene || !THREE) return
  buildScene()
  applyPerspective(props.perspective, true)
})

// ==================== 违规列表变化：重建违规标记 ====================
watch(() => props.violations, () => {
  if (!scene || !THREE || !contentGroup) return
  buildTrailLines(props.points)
  buildViolationMarks()
}, { deep: true })

watch(() => props.playerName, () => {
  if (!scene || !THREE) return
  buildScene()
  applyPerspective(props.perspective, true)
})

// ==================== Resize ====================
function onResize(): void {
  const el = containerRef.value
  if (!el || !camera || !renderer) return
  const w = el.clientWidth
  const h = el.clientHeight
  if (w === 0 || h === 0) return
  camera.aspect = w / h
  camera.updateProjectionMatrix()
  renderer.setSize(w, h, false)
}

// ==================== 资源释放 ====================
function disposeObject(obj: import('three').Object3D): void {
  type MatWithMap = import('three').Material & { map?: import('three').Texture | null }
  obj.traverse((o) => {
    const mesh = o as import('three').Mesh & { material?: import('three').Material | import('three').Material[] }
    if (mesh.geometry) mesh.geometry.dispose()
    const disposeMat = (m: MatWithMap): void => {
      m.map?.dispose()
      m.dispose()
    }
    const mat = mesh.material
    if (Array.isArray(mat)) mat.forEach(disposeMat)
    else if (mat) disposeMat(mat)
  })
}

// ==================== 生命周期 ====================
onMounted(() => {
  void initThree()
})

onBeforeUnmount(() => {
  if (rafId) cancelAnimationFrame(rafId)
  rafId = 0
  resizeObserver?.disconnect()
  resizeObserver = null
  if (controls) { controls.dispose(); controls = null }
  if (contentGroup) { disposeObject(contentGroup); contentGroup = null }
  if (renderer) {
    renderer.dispose()
    renderer.domElement.parentElement?.removeChild(renderer.domElement)
    renderer = null
  }
  // 释放地形材质
  terrainBrownMat?.dispose()
  terrainGreenMat?.dispose()
  terrainWhiteMat?.dispose()
  terrainBrownMat = terrainGreenMat = terrainWhiteMat = null
  scene = null; camera = null
  playerAnchor = null; playerYawGroup = null; playerHeadGroup = null
  nameSprite = null; hitboxGroup = null; gridHelper = null
  trailFull = null; trailPassed = null; violationGroup = null; terrainGroup = null
})
</script>

<template>
  <div ref="containerRef" class="replay-canvas">
    <div v-if="webglFailed" class="webgl-fallback">
      <div class="fallback-icon">⚠</div>
      <div class="fallback-title">WebGL 不可用</div>
      <div class="fallback-desc">
        当前浏览器或设备不支持 WebGL，无法渲染 3D 回放。<br />
        请更换浏览器或开启硬件加速后重试。
      </div>
    </div>
  </div>
</template>

<style scoped>
.replay-canvas {
  width: 100%;
  height: 100%;
  position: relative;
  background: radial-gradient(800px 420px at 30% 40%, rgba(0, 229, 255, 0.06), transparent 60%),
    linear-gradient(180deg, #0a0e14 0%, #10161f 60%, #0a0e14 100%);
}

.replay-canvas :deep(canvas) {
  display: block;
  width: 100% !important;
  height: 100% !important;
}

.webgl-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  text-align: center;
}

.fallback-icon {
  font-size: 38px;
  color: var(--warning);
}

.fallback-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.fallback-desc {
  max-width: 360px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}
</style>
