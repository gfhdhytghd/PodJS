/** Calendar-based local day/night policy; no location request or persistence. */
export interface DailyThemeSchedule {
 mode:"FIXED_TIME"|"SUNRISE_SUNSET";
 lightStartMinutes:number; darkStartMinutes:number;
 latitude:number|null; longitude:number|null;
}
export interface CalendarDay { year: number; month: number; day: number }
export interface SunTimes { sunrise: number; sunset: number }
const radians = (degrees: number) => degrees * Math.PI / 180;
const degrees = (angle: number) => angle * 180 / Math.PI;
const sin = (angle: number) => Math.sin(radians(angle));
const cos = (angle: number) => Math.cos(radians(angle));
const wrap = (value: number, period: number) => ((value % period) + period) % period;

function utcSunHour(dayOfYear: number, latitude: number, longitude: number, sunrise: boolean): number | null {
  const longitudeHour = longitude / 15;
  const approximate = dayOfYear + ((sunrise ? 6 : 18) - longitudeHour) / 24;
  const anomaly = .9856 * approximate - 3.289;
  const longitudeDegrees = wrap(anomaly + 1.916 * sin(anomaly) + .020 * sin(2 * anomaly) + 282.634, 360);
  let ascension = wrap(degrees(Math.atan(.91764 * Math.tan(radians(longitudeDegrees)))), 360);
  ascension = (ascension + Math.floor(longitudeDegrees / 90) * 90 - Math.floor(ascension / 90) * 90) / 15;
  const sinDeclination = .39782 * sin(longitudeDegrees);
  const cosDeclination = Math.cos(Math.asin(sinDeclination));
  const cosHourAngle = (cos(90.833) - sinDeclination * sin(latitude)) / (cosDeclination * cos(latitude));
  if (!Number.isFinite(cosHourAngle) || cosHourAngle < -1 || cosHourAngle > 1) return null;
  const hourAngle = sunrise ? 360 - degrees(Math.acos(cosHourAngle)) : degrees(Math.acos(cosHourAngle));
  return wrap(hourAngle / 15 + ascension - .06571 * approximate - 6.622 - longitudeHour, 24);
}

/** Offset uses Date.getTimezoneOffset's sign. Resolve it at each event for DST parity. */
export function sunTimes(
  day: CalendarDay, latitude: number, longitude: number,
  offsetAt: (utcMillis: number) => number = millis => new Date(millis).getTimezoneOffset(),
): SunTimes | null {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return null;
  const start = Date.UTC(day.year, day.month - 1, day.day);
  const parsed = new Date(start);
  if (parsed.getUTCFullYear() !== day.year || parsed.getUTCMonth() !== day.month - 1 || parsed.getUTCDate() !== day.day) return null;
  const ordinal = Math.floor((start - Date.UTC(day.year, 0, 1)) / 86_400_000) + 1;
  const rise = utcSunHour(ordinal, latitude, longitude, true);
  const set = utcSunHour(ordinal, latitude, longitude, false);
  if (rise === null || set === null) return null;
  const localMinutes = (hour: number) => {
    // Kotlin truncates UTC seconds before applying the zone and dropping seconds.
    const instant = start + Math.trunc(hour * 3600) * 1000;
    return Math.floor(wrap((instant - start) / 60_000 - offsetAt(instant), 1440));
  };
  const sunrise = localMinutes(rise), sunset = localMinutes(set);
  return Number.isFinite(sunrise) && Number.isFinite(sunset) ? { sunrise, sunset } : null;
}

export function scheduledDark(
  schedule: DailyThemeSchedule, day: CalendarDay, minutes: number,
  offsetAt?: (utcMillis: number) => number,
): boolean {
  const solar = schedule.mode === "SUNRISE_SUNSET" && schedule.latitude !== null && schedule.longitude !== null
    ? sunTimes(day, schedule.latitude, schedule.longitude, offsetAt) : null;
  const light = solar?.sunrise ?? schedule.lightStartMinutes;
  const dark = solar?.sunset ?? schedule.darkStartMinutes;
  return dark >= light ? minutes >= dark || minutes < light : minutes >= dark && minutes < light;
}

export function scheduledDarkNow(schedule: DailyThemeSchedule, now = new Date()): boolean {
  return scheduledDark(schedule, { year: now.getFullYear(), month: now.getMonth() + 1, day: now.getDate() }, now.getHours() * 60 + now.getMinutes());
}
