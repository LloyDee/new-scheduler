(() => {
function initializeWeather() {
const weatherCard = document.querySelector('[data-weather]');

if (weatherCard) {
  const field = (name) => weatherCard.querySelector(`[data-weather-${name}]`);
  let loading = false;
  let loaded = false;
  let activeController;
  let disposed = false;
  let lastAttempt = 0;
  const interval = 15 * 60 * 1000;
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
    lastAttempt = Date.now();
    weatherCard.setAttribute('aria-busy', 'true');
    const controller = new AbortController();
    activeController = controller;
    let timeout;
    try {
      if (!window.isSecureContext) throw new Error('Location requires HTTPS. Open the secure application URL.');
      if (!navigator.geolocation) throw new Error('Location is unavailable in this browser.');
      field('status').textContent = 'Getting your location…';
      const position = await new Promise((resolve, reject) => navigator.geolocation.getCurrentPosition(resolve,
        error => reject(new Error(error.code === 1 ? 'Allow location access in your browser to see local weather.'
          : error.code === 3 ? 'Location request timed out. Retrying in 15 minutes.'
          : 'Your location is unavailable. Check device location settings.')),
        { timeout: 10000, maximumAge: 0, enableHighAccuracy: false }));
      if (disposed) return;
      timeout = setTimeout(() => controller.abort(), 12000);
      const params = new URLSearchParams({
        latitude: String(position.coords.latitude), longitude: String(position.coords.longitude),
        current: 'temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m',
        daily: 'temperature_2m_max,temperature_2m_min',
        temperature_unit: 'fahrenheit', wind_speed_unit: 'mph',
        timezone: 'auto', timeformat: 'unixtime', forecast_days: '1',
      });
      const response = await fetch(`https://api.open-meteo.com/v1/forecast?${params}`, { signal: controller.signal });
      if (!response.ok) throw new Error('Weather request failed');
      const data = await response.json();
      if (disposed) return;
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
        hour: 'numeric', minute: '2-digit', timeZone: data.timezone, timeZoneName: 'short',
      }).format(new Date(current.time * 1000));
      field('status').textContent = `Updated ${time}`;
      loaded = true;
    } catch (error) {
      if (disposed) return;
      field('status').textContent = loaded ? `${error.message} Showing last forecast.` : error.message;
    } finally {
      clearTimeout(timeout);
      loading = false;
      weatherCard.setAttribute('aria-busy', 'false');
    }
  }

  loadWeather();
  const timer = setInterval(loadWeather, interval);
  const onVisible = () => {
    if (!document.hidden && Date.now() - lastAttempt >= interval) loadWeather();
  };
  document.addEventListener('visibilitychange', onVisible);
  document.addEventListener('scheduler:before-page', () => {
    disposed = true;
    clearInterval(timer);
    activeController?.abort();
    document.removeEventListener('visibilitychange', onVisible);
  }, { once: true });
}
}
document.addEventListener('scheduler:page', initializeWeather);
initializeWeather();
})();
