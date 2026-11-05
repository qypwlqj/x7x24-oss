import animate from 'tailwindcss-animate';
const c = (name) => `rgb(var(--c-${name}) / <alpha-value>)`;
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{ts,tsx}'],
  darkMode: ['class', '.dark'],
  theme: {
    screens: {
      xs: '480px',
      sm: '640px',
      md: '768px',
      lg: '1024px',
      xl: '1280px',
      '2xl': '1536px',
    },
    colors: {
      transparent: 'transparent', current: 'currentColor',
      canvas: c('canvas'), surface: c('surface'), sunken: c('sunken'), raised: c('raised'),
      line: c('line'), 'line-strong': c('line-strong'),
      ink: c('ink'), 'ink-2': c('ink-2'), 'ink-3': c('ink-3'), 'ink-inverse': c('ink-inverse'),
      primary: c('primary'), 'primary-hover': c('primary-hover'), 'primary-soft': c('primary-soft'), 'primary-ink': c('primary-ink'), 'primary-fg': c('primary-fg'),
      success: c('success'), 'success-soft': c('success-soft'), 'success-ink': c('success-ink'),
      warn: c('warn'), 'warn-soft': c('warn-soft'), 'warn-ink': c('warn-ink'),
      danger: c('danger'), 'danger-soft': c('danger-soft'), 'danger-ink': c('danger-ink'),
      info: c('info'), 'info-soft': c('info-soft'), 'info-ink': c('info-ink'),
      accent: c('accent'), 'accent-soft': c('accent-soft'), 'accent-ink': c('accent-ink'),
      'neon-cyan': c('neon-cyan'), 'neon-magenta': c('neon-magenta'), 'neon-lime': c('neon-lime'), 'neon-violet': c('neon-violet'), 'neon-yellow': c('neon-yellow'), 'neon-red': c('neon-red'),
      background: c('canvas'), foreground: c('ink'), border: c('line'), input: c('line-strong'), ring: c('focus'),
      card: { DEFAULT: c('surface'), foreground: c('ink') }, popover: { DEFAULT: c('raised'), foreground: c('ink') },
      muted: { DEFAULT: c('sunken'), foreground: c('ink-3') }, secondary: { DEFAULT: c('raised'), foreground: c('ink') },
      destructive: { DEFAULT: c('danger'), foreground: c('ink-inverse') },
      focus: c('focus'), scrim: 'rgb(var(--c-scrim) / 0.65)',
    },
    fontFamily: {
      sans: ['var(--font-sans)'], mono: ['var(--font-mono)'], display: ['var(--font-display)'],
    },
    fontSize: {
      caption: ['12px', '16px'], xs: ['13px', '18px'], sm: ['14px', '20px'], base: ['15px', '22px'], lg: ['17px', '24px'],
      xl: ['20px', '28px'], '2xl': ['26px', '32px'], display: ['34px', '38px'],
    },
    borderRadius: { none: '0', sm: 'var(--radius-sm)', DEFAULT: 'var(--radius-md)', md: 'var(--radius-md)', lg: 'var(--radius-lg)', full: '9999px' },
    boxShadow: { none: 'none', xs: 'var(--shadow-xs)', sm: 'var(--shadow-sm)', md: 'var(--shadow-md)', pop: 'var(--shadow-pop)', 'glow-cyan': 'var(--glow-cyan)', 'glow-magenta': 'var(--glow-magenta)', 'glow-lime': 'var(--glow-lime)', 'glow-red': 'var(--glow-red)' },
    transitionDuration: { DEFAULT: 'var(--dur-base)', fast: 'var(--dur-fast)', base: 'var(--dur-base)', slow: 'var(--dur-slow)' },
    transitionTimingFunction: { DEFAULT: 'var(--ease-out)', out: 'var(--ease-out)', 'in-out': 'var(--ease-in-out)' },
    extend: {
      spacing: {
        page: 'var(--space-page)',
        card: 'var(--space-card)',
        gap: 'var(--space-gap)',
        sidebar: 'var(--sidebar-w)',
        'sidebar-rail': 'var(--sidebar-rail-w)',
      },
      keyframes: { 'slide-in': { from: { transform: 'translateX(24px)', opacity: '0' }, to: { transform: 'none', opacity: '1' } }, 'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } }, pulse2: { '50%': { opacity: '.45' } } },
      animation: { 'slide-in': 'slide-in var(--dur-slow) var(--ease-out)', 'fade-in': 'fade-in var(--dur-base) var(--ease-out)', pulse2: 'pulse2 1.6s ease-in-out infinite' },
    },
  },
  plugins: [animate],
};
