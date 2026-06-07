<script setup lang="ts">
import { computed } from "vue";

/**
 * 月历组件 — 单月视图, 范围高亮, 可点选.
 * 受控模式: 父组件管理 year/month/startDate/endDate 状态.
 */
const props = defineProps<{
  year: number;
  month: number; // 0-11
  startDate: string; // YYYY-MM-DD
  endDate: string; // YYYY-MM-DD
  minDate?: string;
  maxDate?: string;
}>();

const emit = defineEmits<{
  (e: "update:year", year: number): void;
  (e: "update:month", month: number): void;
  (e: "select", date: string): void;
}>();

const MONTH_NAMES = [
  "1月", "2月", "3月", "4月", "5月", "6月",
  "7月", "8月", "9月", "10月", "11月", "12月",
];
const WEEKDAY_NAMES = ["日", "一", "二", "三", "四", "五", "六"];

function pad2(n: number): string {
  return n < 10 ? "0" + n : String(n);
}
function toDateStr(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

interface DayCell {
  date: string;
  day: number;
  isCurrentMonth: boolean;
}

// 平铺成 42 格 (6 周) 方便 v-for
const days = computed<DayCell[]>(() => {
  const firstDay = new Date(props.year, props.month, 1);
  const startDayOfWeek = firstDay.getDay(); // 0=Sun
  const lastDay = new Date(props.year, props.month + 1, 0);
  const daysInMonth = lastDay.getDate();

  const result: DayCell[] = [];

  // 上月尾部
  for (let i = startDayOfWeek - 1; i >= 0; i--) {
    const d = new Date(props.year, props.month, -i);
    result.push({ date: toDateStr(d), day: d.getDate(), isCurrentMonth: false });
  }
  // 本月
  for (let d = 1; d <= daysInMonth; d++) {
    const date = new Date(props.year, props.month, d);
    result.push({ date: toDateStr(date), day: d, isCurrentMonth: true });
  }
  // 下月头部 (补到 42)
  let nextDay = 1;
  while (result.length < 42) {
    const d = new Date(props.year, props.month + 1, nextDay++);
    result.push({ date: toDateStr(d), day: d.getDate(), isCurrentMonth: false });
  }
  return result;
});

function prevMonth() {
  if (props.month === 0) {
    emit("update:year", props.year - 1);
    emit("update:month", 11);
  } else {
    emit("update:month", props.month - 1);
  }
}
function nextMonth() {
  if (props.month === 11) {
    emit("update:year", props.year + 1);
    emit("update:month", 0);
  } else {
    emit("update:month", props.month + 1);
  }
}

const todayStr = toDateStr(new Date());

function isToday(date: string): boolean {
  return date === todayStr;
}
function isStart(date: string): boolean {
  return date === props.startDate;
}
function isEnd(date: string): boolean {
  return date === props.endDate;
}
function isInRange(date: string): boolean {
  if (!props.startDate || !props.endDate) return false;
  return date >= props.startDate && date <= props.endDate;
}
function isDisabled(date: string): boolean {
  if (props.minDate && date < props.minDate) return true;
  if (props.maxDate && date > props.maxDate) return true;
  return false;
}
function clickDay(d: DayCell) {
  if (!d.isCurrentMonth) return;
  if (isDisabled(d.date)) return;
  emit("select", d.date);
}
</script>

<template>
  <div class="usage-calendar">
    <div class="usage-calendar-header">
      <button type="button" class="usage-calendar-nav" @click="prevMonth">‹</button>
      <span class="usage-calendar-title">{{ year }}年{{ MONTH_NAMES[month] }}</span>
      <button type="button" class="usage-calendar-nav" @click="nextMonth">›</button>
    </div>
    <div class="usage-calendar-weekdays">
      <span v-for="w in WEEKDAY_NAMES" :key="w">{{ w }}</span>
    </div>
    <div class="usage-calendar-days">
      <button
        v-for="(d, i) in days"
        :key="i"
        type="button"
        class="usage-calendar-day"
        :class="{
          'other-month': !d.isCurrentMonth,
          'today': isToday(d.date),
          'in-range': isInRange(d.date),
          'start': isStart(d.date),
          'end': isEnd(d.date),
          'disabled': isDisabled(d.date),
        }"
        :disabled="isDisabled(d.date) || !d.isCurrentMonth"
        @click="clickDay(d)"
      >
        {{ d.day }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.usage-calendar {
  font-size: 13px;
  user-select: none;
}
.usage-calendar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0 8px;
  font-weight: 500;
  color: #111827;
}
.usage-calendar-title {
  font-size: 13px;
}
.usage-calendar-nav {
  width: 24px;
  height: 24px;
  border: 0;
  background: transparent;
  color: #6b7280;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s;
}
.usage-calendar-nav:hover {
  background: #f3f4f6;
  color: #111827;
}
.usage-calendar-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  padding-bottom: 4px;
  color: #9ca3af;
  font-size: 11px;
  text-align: center;
}
.usage-calendar-weekdays span {
  padding: 2px 0;
}
.usage-calendar-days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 1px;
}
.usage-calendar-day {
  height: 30px;
  border: 0;
  background: transparent;
  font-size: 12.5px;
  color: #374151;
  cursor: pointer;
  border-radius: 4px;
  padding: 0;
  position: relative;
}
.usage-calendar-day:hover:not(:disabled):not(.start):not(.end) {
  background: #f3f4f6;
}
.usage-calendar-day.other-month {
  color: #d1d5db;
}
.usage-calendar-day.today {
  font-weight: 600;
  color: #4f46e5;
}
.usage-calendar-day.today::after {
  content: "";
  position: absolute;
  left: 50%;
  bottom: 2px;
  transform: translateX(-50%);
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #4f46e5;
}
.usage-calendar-day.in-range {
  background: #eef2ff;
  border-radius: 0;
}
.usage-calendar-day.start,
.usage-calendar-day.end {
  background: #4f46e5;
  color: #fff;
  font-weight: 500;
  border-radius: 4px;
}
.usage-calendar-day.start.end {
  border-radius: 4px;
}
.usage-calendar-day.disabled {
  color: #e5e7eb;
  cursor: not-allowed;
}
</style>
