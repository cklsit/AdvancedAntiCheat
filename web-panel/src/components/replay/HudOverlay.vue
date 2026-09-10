<script setup lang="ts">
import { computed } from 'vue'
import type { HudFrame } from '@/types/replay'
const props = defineProps<{
  hud: HudFrame | null
  /** 是否显示（实时模式才显示，历史模式不显示） */
  show?: boolean
}>()

// ==================== 健康 / 饥饿 / 护甲 计算 ====================
/** 心型图标数组：每个代表 2 HP，实心/半心/空心 */
const hearts = computed(() => {
  if (!props.hud) return []
  const maxHP = Math.max(1, props.hud.maxHealth || 20)
  const hp = Math.max(0, Math.min(props.hud.health, maxHP))
  // 最大显示 10 格（按比例映射到 10 格）
  const slots = 10
  const hpPerSlot = maxHP / slots
  const arr: Array<'full' | 'half' | 'empty'> = []
  for (let i = 0; i < slots; i++) {
    const slotStart = i * hpPerSlot
    const slotEnd = (i + 1) * hpPerSlot
    if (hp >= slotEnd) arr.push('full')
    else if (hp >= slotStart + hpPerSlot * 0.5) arr.push('half')
    else arr.push('empty')
  }
  return arr
})

const hungerBars = computed(() => {
  if (!props.hud) return []
  const hunger = Math.max(0, Math.min(props.hud.hunger, 20))
  const slots = 10
  const arr: Array<'full' | 'half' | 'empty'> = []
  for (let i = 0; i < slots; i++) {
    const v = hunger - i * 2
    if (v >= 2) arr.push('full')
    else if (v >= 1) arr.push('half')
    else arr.push('empty')
  }
  return arr
})

const armorBars = computed(() => {
  if (!props.hud) return []
  const armor = Math.max(0, Math.min(props.hud.armor, 20))
  const slots = 10
  const arr: Array<'full' | 'half' | 'empty'> = []
  for (let i = 0; i < slots; i++) {
    const v = armor - i * 2
    if (v >= 2) arr.push('full')
    else if (v >= 1) arr.push('half')
    else arr.push('empty')
  }
  return arr
})

const airBubbles = computed(() => {
  if (!props.hud || props.hud.air == null) return []
  const air = Math.max(0, Math.min(props.hud.air, 300))
  const slots = 10
  const perSlot = 300 / slots
  const arr: Array<'full' | 'half' | 'empty'> = []
  for (let i = 0; i < slots; i++) {
    const v = air - i * perSlot
    if (v >= perSlot) arr.push('full')
    else if (v >= perSlot * 0.5) arr.push('half')
    else arr.push('empty')
  }
  return arr
})

// ==================== Hotbar 物品映射 ====================
/**
 * 物品 id → 缩写 + 颜色
 * - minecraft:diamond_sword → DS + 蓝色
 * - fallback：取命名空间后部分首字母缩写，颜色用 hash 生成
 */
function getItemChip(itemId: string | null): { abbr: string; bg: string; fg: string } {
  if (!itemId) return { abbr: '', bg: '#2a2a2a', fg: '#888' }
  // 去掉 minecraft: 前缀
  const clean = itemId.replace(/^minecraft:/i, '')
  // 已知映射
  const known: Record<string, { abbr: string; bg: string; fg: string }> = {
    diamond_sword: { abbr: 'DS', bg: '#3b82f6', fg: '#fff' },
    diamond_pickaxe: { abbr: 'DP', bg: '#3b82f6', fg: '#fff' },
    diamond_axe: { abbr: 'DA', bg: '#3b82f6', fg: '#fff' },
    diamond_shovel: { abbr: 'DSv', bg: '#3b82f6', fg: '#fff' },
    diamond_hoe: { abbr: 'DH', bg: '#3b82f6', fg: '#fff' },
    iron_sword: { abbr: 'IS', bg: '#9ca3af', fg: '#000' },
    iron_pickaxe: { abbr: 'IP', bg: '#9ca3af', fg: '#000' },
    iron_axe: { abbr: 'IA', bg: '#9ca3af', fg: '#000' },
    gold_sword: { abbr: 'GS', bg: '#eab308', fg: '#000' },
    golden_sword: { abbr: 'GS', bg: '#eab308', fg: '#000' },
    golden_pickaxe: { abbr: 'GP', bg: '#eab308', fg: '#000' },
    netherite_sword: { abbr: 'NS', bg: '#57534e', fg: '#fff' },
    netherite_pickaxe: { abbr: 'NP', bg: '#57534e', fg: '#fff' },
    wooden_sword: { abbr: 'WS', bg: '#a16207', fg: '#fff' },
    stone_sword: { abbr: 'SS', bg: '#78716c', fg: '#fff' },
    bow: { abbr: 'BW', bg: '#a16207', fg: '#fff' },
    crossbow: { abbr: 'CB', bg: '#78350f', fg: '#fff' },
    shield: { abbr: 'SH', bg: '#475569', fg: '#fff' },
    trident: { abbr: 'TR', bg: '#06b6d4', fg: '#fff' },
    totem_of_undying: { abbr: 'TM', bg: '#dc2626', fg: '#fff' },
    apple: { abbr: 'AP', bg: '#ef4444', fg: '#fff' },
    golden_apple: { abbr: 'GA', bg: '#eab308', fg: '#000' },
    enchanted_golden_apple: { abbr: 'EG', bg: '#eab308', fg: '#7c3aed' },
    bread: { abbr: 'BR', bg: '#d97706', fg: '#fff' },
    cooked_beef: { abbr: 'CK', bg: '#991b1b', fg: '#fff' },
    steak: { abbr: 'SK', bg: '#991b1b', fg: '#fff' },
    porkchop: { abbr: 'PK', bg: '#f472b6', fg: '#000' },
    cooked_porkchop: { abbr: 'CP', bg: '#be185d', fg: '#fff' },
    water_bucket: { abbr: 'WB', bg: '#0ea5e9', fg: '#fff' },
    lava_bucket: { abbr: 'LB', bg: '#f97316', fg: '#fff' },
    milk_bucket: { abbr: 'MB', bg: '#e5e7eb', fg: '#000' },
    ender_pearl: { abbr: 'EP', bg: '#059669', fg: '#fff' },
    blaze_rod: { abbr: 'BR', bg: '#f59e0b', fg: '#000' },
    torch: { abbr: 'TH', bg: '#f59e0b', fg: '#000' },
    cobblestone: { abbr: 'CB', bg: '#6b7280', fg: '#fff' },
    dirt: { abbr: 'DR', bg: '#78350f', fg: '#fff' },
    sand: { abbr: 'SD', bg: '#fde68a', fg: '#000' },
    gravel: { abbr: 'GV', bg: '#737373', fg: '#fff' },
    stone: { abbr: 'ST', bg: '#57534e', fg: '#fff' },
    obsidian: { abbr: 'OB', bg: '#2e1065', fg: '#fff' },
    tnt: { abbr: 'TNT', bg: '#dc2626', fg: '#fff' },
    diamond_block: { abbr: 'DB', bg: '#22d3ee', fg: '#000' },
    emerald_block: { abbr: 'EB', bg: '#10b981', fg: '#000' },
    gold_block: { abbr: 'GB', bg: '#facc15', fg: '#000' },
    iron_block: { abbr: 'IB', bg: '#d4d4d4', fg: '#000' },
    oak_planks: { abbr: 'OP', bg: '#a16207', fg: '#fff' },
    crafting_table: { abbr: 'CT', bg: '#92400e', fg: '#fff' },
    furnace: { abbr: 'FR', bg: '#4b5563', fg: '#fff' },
    chest: { abbr: 'CH', bg: '#92400e', fg: '#fff' },
    bed: { abbr: 'BD', bg: '#ec4899', fg: '#fff' },
    composter: { abbr: 'CP', bg: '#78350f', fg: '#fff' },
  }
  const lower = clean.toLowerCase()
  if (known[lower]) return known[lower]
  // fallback: 根据下划线分割取首字母，最多 3 个
  const parts = clean.split(/[_/]/).filter(Boolean)
  let abbr = ''
  for (const p of parts) {
    if (p.length > 0) abbr += p[0].toUpperCase()
    if (abbr.length >= 3) break
  }
  if (!abbr) abbr = clean.slice(0, 2).toUpperCase()
  // hash 颜色
  let hash = 0
  for (let i = 0; i < clean.length; i++) {
    hash = (hash * 31 + clean.charCodeAt(i)) >>> 0
  }
  const hue = hash % 360
  const bg = `hsl(${hue}, 55%, 42%)`
  const fg = hue > 50 && hue < 200 ? '#000' : '#fff'
  return { abbr, bg, fg }
}

const hotbarItems = computed(() => {
  if (!props.hud) return []
  return (props.hud.hotbar ?? Array(9).fill(null)).map((slot, idx) => ({
    idx,
    item: slot,
    chip: slot?.id ? getItemChip(slot.id) : null,
    selected: idx === props.hud!.hotbarSlot,
  }))
})

// XP bar 渐变
const xpPercent = computed(() => {
  if (!props.hud) return 0
  return Math.max(0, Math.min(1, props.hud.xp)) * 100
})

// ==================== 准星目标 / 坐标 / 完整背包（需求 4） ====================

const coordsText = computed(() => {
  const h = props.hud
  if (!h || h.x == null) return ''
  const x = h.x.toFixed(1)
  const y = h.y?.toFixed(1) ?? '?'
  const z = h.z?.toFixed(1) ?? '?'
  return `XYZ  ${x} / ${y} / ${z}`
})

const crosshairText = computed(() => {
  const h = props.hud
  if (!h || h.tk == null || h.tk === 0) return ''
  const dist = h.td != null ? `${h.td.toFixed(1)}m` : ''
  if (h.tk === 1) {
    const type = h.tt ?? '实体'
    const name = h.tn ? `「${h.tn}」` : ''
    return `准星  ${type}${name} ${dist}`.trim()
  }
  if (h.tk === 2) {
    const block = h.tb ?? '方块'
    return `准星  ${block} ${dist}`.trim()
  }
  return ''
})

const crosshairKind = computed<'entity' | 'block' | 'none'>(() => {
  const h = props.hud
  if (!h || h.tk == null || h.tk === 0) return 'none'
  return h.tk === 1 ? 'entity' : 'block'
})

/** 完整 36 格背包：9 列 × 4 行，稀疏采样，null 保留空槽 */
const inventoryGrid = computed(() => {
  const h = props.hud
  if (!h || !h.finv) return null
  const finv = h.finv
  const rows: Array<Array<{ slot: number; id: string | null }>> = []
  for (let r = 0; r < 4; r++) {
    const row: Array<{ slot: number; id: string | null }> = []
    for (let c = 0; c < 9; c++) {
      const idx = r * 9 + c
      row.push({ slot: idx, id: finv[idx] ?? null })
    }
    rows.push(row)
  }
  return rows
})
</script>

<template>
  <div v-if="show !== false" class="hud-overlay">
    <template v-if="hud">
      <!-- ========== 顶部信息栏（坐标 / 准星目标） ========== -->
      <div v-if="coordsText || crosshairText" class="hud-info-bar">
        <span v-if="coordsText" class="hud-coords">{{ coordsText }}</span>
        <span
          v-if="crosshairText"
          class="hud-crosshair"
          :class="{ entity: crosshairKind === 'entity', block: crosshairKind === 'block' }"
        >
          {{ crosshairText }}
        </span>
      </div>

      <!-- ========== 顶部状态栏（生命 / 护甲 / 饥饿 / 氧气） ========== -->
      <div class="hud-status-row">
        <!-- 生命值（心） -->
        <div class="bar-group hearts">
          <span
            v-for="(h, i) in hearts"
            :key="`h-${i}`"
            class="mc-heart"
            :class="h"
          >
            <svg v-if="h === 'full'" viewBox="0 0 24 24" class="hud-icon">
              <path d="M12 21s-7.5-4.5-10-9.5C.5 8 2.5 4 6 4c2 0 3.5 1 4 2.5C10.5 5 12 4 14 4c3.5 0 5.5 4 4 7.5C19.5 16.5 12 21 12 21z" fill="#e74c3c" stroke="#7f1d1d" stroke-width="1.2"/>
            </svg>
            <svg v-else-if="h === 'half'" viewBox="0 0 24 24" class="hud-icon">
              <defs>
                <linearGradient id="halfHeart" x1="0" x2="1">
                  <stop offset="50%" stop-color="#e74c3c"/>
                  <stop offset="50%" stop-color="#3f3f46"/>
                </linearGradient>
              </defs>
              <path d="M12 21s-7.5-4.5-10-9.5C.5 8 2.5 4 6 4c2 0 3.5 1 4 2.5C10.5 5 12 4 14 4c3.5 0 5.5 4 4 7.5C19.5 16.5 12 21 12 21z" fill="url(#halfHeart)" stroke="#7f1d1d" stroke-width="1.2"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" class="hud-icon">
              <path d="M12 21s-7.5-4.5-10-9.5C.5 8 2.5 4 6 4c2 0 3.5 1 4 2.5C10.5 5 12 4 14 4c3.5 0 5.5 4 4 7.5C19.5 16.5 12 21 12 21z" fill="#3f3f46" stroke="#18181b" stroke-width="1.2"/>
            </svg>
          </span>
        </div>

        <!-- 护甲（胸甲） -->
        <div v-if="armorBars.some(a => a !== 'empty')" class="bar-group armors">
          <span
            v-for="(a, i) in armorBars"
            :key="`a-${i}`"
            class="mc-armor"
            :class="a"
          >
            <svg v-if="a === 'full'" viewBox="0 0 24 24" class="hud-icon">
              <path d="M6 3h12l2 4v4c0 3-2 6-8 7-6-1-8-4-8-7V7L6 3z" fill="#94a3b8" stroke="#334155" stroke-width="1"/>
              <path d="M10 3h4v4h-4z" fill="#64748b"/>
            </svg>
            <svg v-else-if="a === 'half'" viewBox="0 0 24 24" class="hud-icon">
              <defs>
                <linearGradient id="halfArmor" x1="0" x2="1">
                  <stop offset="50%" stop-color="#94a3b8"/>
                  <stop offset="50%" stop-color="#3f3f46"/>
                </linearGradient>
              </defs>
              <path d="M6 3h12l2 4v4c0 3-2 6-8 7-6-1-8-4-8-7V7L6 3z" fill="url(#halfArmor)" stroke="#334155" stroke-width="1"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" class="hud-icon">
              <path d="M6 3h12l2 4v4c0 3-2 6-8 7-6-1-8-4-8-7V7L6 3z" fill="#3f3f46" stroke="#18181b" stroke-width="1"/>
            </svg>
          </span>
        </div>

        <!-- 氧气条（水中） -->
        <div v-if="airBubbles.length" class="bar-group airs">
          <span
            v-for="(a, i) in airBubbles"
            :key="`air-${i}`"
            class="mc-air"
            :class="a"
          >
            <svg v-if="a === 'full'" viewBox="0 0 24 24" class="hud-icon">
              <circle cx="12" cy="12" r="9" fill="#60a5fa" stroke="#1e3a8a" stroke-width="1"/>
              <ellipse cx="9" cy="9" rx="2.5" ry="2" fill="#bfdbfe" opacity="0.8"/>
            </svg>
            <svg v-else-if="a === 'half'" viewBox="0 0 24 24" class="hud-icon">
              <defs>
                <linearGradient id="halfAir" x1="0" x2="1">
                  <stop offset="50%" stop-color="#60a5fa"/>
                  <stop offset="50%" stop-color="#3f3f46"/>
                </linearGradient>
              </defs>
              <circle cx="12" cy="12" r="9" fill="url(#halfAir)" stroke="#1e3a8a" stroke-width="1"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" class="hud-icon">
              <circle cx="12" cy="12" r="9" fill="#3f3f46" stroke="#18181b" stroke-width="1"/>
            </svg>
          </span>
        </div>

        <!-- 饥饿（鸡腿） -->
        <div class="bar-group hungers">
          <span
            v-for="(h, i) in hungerBars"
            :key="`hg-${i}`"
            class="mc-hunger"
            :class="h"
          >
            <svg v-if="h === 'full'" viewBox="0 0 24 24" class="hud-icon">
              <path d="M6 4c2 0 4 2 4 5 0 1.5-.5 3-1.5 4.5C10 14.5 12 17 12 20c-2.5 0-5-1.5-6-4-1-.5-2-2-2-3.5C4 10.5 5 8 6 4z" fill="#b45309" stroke="#451a03" stroke-width="1"/>
              <path d="M12 10c1.5-1 3-2 4-1.5 1.5.7 2 2.5 1 5s-2.5 4.5-5 5" fill="none" stroke="#f59e0b" stroke-width="2.5" stroke-linecap="round"/>
            </svg>
            <svg v-else-if="h === 'half'" viewBox="0 0 24 24" class="hud-icon">
              <defs>
                <linearGradient id="halfHunger" x1="0" x2="1">
                  <stop offset="50%" stop-color="#b45309"/>
                  <stop offset="50%" stop-color="#3f3f46"/>
                </linearGradient>
              </defs>
              <path d="M6 4c2 0 4 2 4 5 0 1.5-.5 3-1.5 4.5C10 14.5 12 17 12 20c-2.5 0-5-1.5-6-4-1-.5-2-2-2-3.5C4 10.5 5 8 6 4z" fill="url(#halfHunger)" stroke="#451a03" stroke-width="1"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" class="hud-icon">
              <path d="M6 4c2 0 4 2 4 5 0 1.5-.5 3-1.5 4.5C10 14.5 12 17 12 20c-2.5 0-5-1.5-6-4-1-.5-2-2-2-3.5C4 10.5 5 8 6 4z" fill="#3f3f46" stroke="#18181b" stroke-width="1"/>
            </svg>
          </span>
        </div>
      </div>

      <!-- ========== XP Bar（在 hotbar 正下方上方，即 hotbar 前） ========== -->
      <div class="hud-xp-row">
        <div class="xp-bar">
          <div
            class="xp-fill"
            :style="{ width: xpPercent + '%' }"
          ></div>
        </div>
        <div class="xp-level">{{ hud.level }}</div>
      </div>

      <!-- ========== Hotbar 快捷栏 ========== -->
      <div class="hud-hotbar-row">
        <div
          v-for="hb in hotbarItems"
          :key="hb.idx"
          class="hotbar-slot"
          :class="{ selected: hb.selected }"
        >
          <template v-if="hb.item && hb.chip">
            <div
              class="item-chip"
              :style="{ background: hb.chip.bg, color: hb.chip.fg }"
            >
              {{ hb.chip.abbr }}
            </div>
            <span v-if="hb.item.count > 1" class="item-count">
              {{ hb.item.count }}
            </span>
          </template>
        </div>
      </div>

      <!-- ========== 完整 36 格背包（需求 4） ========== -->
      <div v-if="inventoryGrid" class="hud-full-inventory">
        <div v-for="(row, ri) in inventoryGrid" :key="`invr-${ri}`" class="full-inv-row">
          <div
            v-for="cell in row"
            :key="`invc-${cell.slot}`"
            class="full-inv-slot"
            :class="{ filled: cell.id }"
          >
            <span v-if="cell.id" class="full-inv-chip">
              {{ cell.id.replace(/^minecraft:/i, '').split('_').map(s => s[0]).join('').slice(0, 3).toUpperCase() }}
            </span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.hud-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  padding-bottom: 24px;
  z-index: 10;
  font-family: var(--font-mono);
  image-rendering: pixelated;
}

/* ==================== 状态条（心 / 护甲 / 饥饿 / 氧气） ==================== */
.hud-status-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.bar-group {
  display: flex;
  gap: 1px;
  align-items: center;
}
.hud-icon {
  width: 18px;
  height: 18px;
  display: block;
  filter: drop-shadow(0 1px 1px rgba(0,0,0,0.8));
}
.mc-heart, .mc-armor, .mc-air, .mc-hunger {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
/* 饥饿条在 MC 中是倒序填充（从右往左视觉），这里保持正常顺序即可 */

/* ==================== XP Bar ==================== */
.hud-xp-row {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 4px;
  position: relative;
}
.xp-bar {
  width: 360px;
  max-width: 70vw;
  height: 10px;
  background: rgba(0,0,0,0.55);
  border: 1.5px solid #000;
  border-radius: 1px;
  overflow: hidden;
  box-shadow: inset 0 0 0 1px rgba(255,255,255,0.08);
}
.xp-fill {
  height: 100%;
  background: linear-gradient(
    90deg,
    #16a34a 0%,
    #22c55e 35%,
    #eab308 70%,
    #22d3ee 100%
  );
  box-shadow: 0 0 6px rgba(34, 197, 94, 0.5);
  transition: width 0.1s linear;
}
.xp-level {
  position: absolute;
  top: -14px;
  left: 50%;
  transform: translateX(-50%);
  color: #7dd3fc;
  font-size: 11px;
  font-weight: 700;
  text-shadow: 0 1px 2px rgba(0,0,0,0.9), 0 0 4px rgba(14, 165, 233, 0.6);
  letter-spacing: 1px;
}

/* ==================== Hotbar ==================== */
.hud-hotbar-row {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: rgba(0,0,0,0.6);
  border: 2px solid #1a1a1a;
  border-radius: 3px;
  box-shadow:
    0 2px 0 rgba(0,0,0,0.6),
    inset 0 1px 0 rgba(255,255,255,0.06);
}
.hotbar-slot {
  width: 48px;
  height: 48px;
  background: #262626;
  border: 2px solid #0a0a0a;
  border-radius: 2px;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow:
    inset 1px 1px 0 rgba(255,255,255,0.08),
    inset -1px -1px 0 rgba(0,0,0,0.5);
}
.hotbar-slot.selected {
  border-color: #facc15;
  box-shadow:
    0 0 0 1.5px #facc15,
    0 0 8px rgba(250, 204, 21, 0.55),
    inset 1px 1px 0 rgba(255,255,255,0.15);
  background: #3f3a20;
}
.item-chip {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.3px;
  border-radius: 3px;
  border: 1px solid rgba(0,0,0,0.45);
  text-shadow: 0 1px 0 rgba(0,0,0,0.4);
}
.item-count {
  position: absolute;
  right: 2px;
  bottom: 0;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  text-shadow:
    1px 1px 0 #000,
    -1px 1px 0 #000,
    1px -1px 0 #000,
    -1px -1px 0 #000;
  letter-spacing: 0.5px;
  line-height: 1;
}

/* ==================== 顶部信息栏（坐标 / 准星目标） ==================== */
.hud-info-bar {
  position: absolute;
  top: 12px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 16px;
  gap: 8px;
  pointer-events: none;
}
.hud-coords {
  font-size: 12px;
  font-weight: 600;
  color: #9fe1cb;
  background: rgba(0,0,0,0.55);
  padding: 3px 8px;
  border-radius: 3px;
  text-shadow: 0 1px 2px rgba(0,0,0,0.9);
  tab-size: 4;
}
.hud-crosshair {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 3px;
  text-shadow: 0 1px 2px rgba(0,0,0,0.9);
}
.hud-crosshair.entity {
  color: #fca5a5;
  background: rgba(153, 27, 27, 0.7);
  border: 1px solid rgba(248, 113, 113, 0.5);
}
.hud-crosshair.block {
  color: #fdba74;
  background: rgba(120, 53, 15, 0.7);
  border: 1px solid rgba(251, 146, 60, 0.5);
}

/* ==================== 完整 36 格背包 ==================== */
.hud-full-inventory {
  position: absolute;
  right: 12px;
  bottom: 96px;
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 3px;
  background: rgba(0,0,0,0.55);
  border: 1px solid rgba(0,0,0,0.8);
  border-radius: 3px;
  pointer-events: none;
}
.full-inv-row {
  display: flex;
  gap: 1px;
}
.full-inv-slot {
  width: 20px;
  height: 20px;
  background: #1c1c1c;
  border: 1px solid #0a0a0a;
  border-radius: 1px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.full-inv-slot.filled {
  background: #2a2a2a;
}
.full-inv-chip {
  font-size: 7px;
  font-weight: 700;
  color: #d4d4d8;
  letter-spacing: 0.2px;
  line-height: 1;
}
</style>
