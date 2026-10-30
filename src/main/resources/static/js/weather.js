(() => {
function initializeWeather() {
const weatherCard = document.querySelector('[data-weather]');

if (weatherCard) {
  const field = (name) => weatherCard.querySelector(`[data-weather-${name}]`);
  let loading = false;
  let loaded = false;
  let activeController;
  const rounded = (value, suffix = '') => Number.isFinite(value) ? `${Math.round(value)}${suffix}` : '—';
  const conditions = (code, day) => {
    if (code === 0) return [day ? '☀' : '☾', day ? 'Clear sky' : 'Clear night'];
    if (code === 1) return [day ? '🌤' : '☾', 'Mostly clear'];
    if (code === 2) return ['⛅', 'Partly cloudy'];
    if (code === 3) return ['☁', 'Overcast'];
    if ([45, 48].includes(code)) return ['🌫', 'Foggy'];
    if ([51, 53, 55, 56, 57].includes(code)) return ['🌦', 'Drizzle'];
    if ([61, 63, 65, 66, 67, 80, 81, 82].includes(code)) return ['🌧', 'Rain'];
    if ([71, 73, 75, 77, 85, 86].includes(code)) return ['❄', 'Snow'];
    if ([95, 96, 99].includes(code)) return ['⛈', 'Thunderstorms'];
    return ['☁', 'Current weather'];
  };

  async function loadWeather() {
    if (loading) return;
    loading = true;
    weatherCard.setAttribute('aria-busy', 'true');
    const controller = new AbortController();
    activeController = controller;
    const timeout = setTimeout(() => controller.abort(), 12000);
    try {
      const params = new URLSearchParams({
        latitude: '29.4241', longitude: '-98.4936',
        current: 'temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m',
        daily: 'temperature_2m_max,temperature_2m_min',
        temperature_unit: 'fahrenheit', wind_speed_unit: 'mph',
        timezone: 'America/Chicago', timeformat: 'unixtime', forecast_days: '1',
      });
      const response = await fetch(`https://api.open-meteo.com/v1/forecast?${params}`, { signal: controller.signal });
      if (!response.ok) throw new Error('Weather request failed');
      const data = await response.json();
      const current = data.current;
      if (!current || !Number.isFinite(current.temperature_2m) || !Number.isFinite(current.time)) {
        throw new Error('Weather data unavailable');
      }
      const [icon, description] = conditions(current.weather_code, current.is_day);
      field('icon').textContent = icon;
      field('temperature').textContent = rounded(current.temperature_2m);
      field('condition').textContent = description;
      field('feels').textContent = rounded(current.apparent_temperature, '°');
      field('humidity').textContent = rounded(current.relative_humidity_2m, '%');
      field('wind').textContent = rounded(current.wind_speed_10m, ' mph');
      field('high').textContent = rounded(data.daily?.temperature_2m_max?.[0], '°');
      field('low').textContent = rounded(data.daily?.temperature_2m_min?.[0], '°');
      const time = new Intl.DateTimeFormat('en-US', {
        hour: 'numeric', minute: '2-digit', timeZone: 'America/Chicago', timeZoneName: 'short',
      }).format(new Date(current.time * 1000));
      field('status').textContent = `Updated ${time}`;
      loaded = true;
    } catch {
      field('status').textContent = loaded ? 'Update failed · showing last forecast' : 'Weather unavailable. Retrying automatically…';
    } finally {
      clearTimeout(timeout);
      loading = false;
      weatherCard.setAttribute('aria-busy', 'false');
    }
  }

  loadWeather();
  const timer = setInterval(loadWeather, 30 * 1000);
  document.addEventListener('scheduler:before-page', () => {
    clearInterval(timer);
    activeController?.abort();
  }, { once: true });
}
}
document.addEventListener('scheduler:page', initializeWeather);
initializeWeather();
})();
