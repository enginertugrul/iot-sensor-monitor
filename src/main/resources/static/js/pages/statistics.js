(() => {
    'use strict';

    const rangeForm = document.getElementById('statisticsRangeForm');
    const connectionStatus = document.getElementById('statisticsConnectionStatus');
    const connectionMessage = document.getElementById('statisticsConnectionMessage');

    let connectionStatusTimer = null;
    let connectionWasOffline = navigator.onLine === false;

    const networkControls = Array.from(document.querySelectorAll('[data-statistics-network-control]'));

    const initialNetworkDisabled = new WeakMap(
        networkControls.map(control => [control,control.disabled])
    );

    function updateConnectionStatus(announceRestoration = false) {
        const offline = navigator.onLine === false;

        networkControls.forEach(control => {
            control.disabled = initialNetworkDisabled.get(control) || offline;
        });

        if (!connectionStatus || !connectionMessage) {
            connectionWasOffline = offline;
            return;
        }

        window.clearTimeout(connectionStatusTimer);

        if (offline) {
            connectionStatus.dataset.state = 'offline';
            connectionMessage.textContent = connectionStatus.dataset.offlineMessage;
            connectionStatus.hidden = false;
        } else if (announceRestoration && connectionWasOffline) {
            connectionStatus.dataset.state = 'restored';
            connectionMessage.textContent = connectionStatus.dataset.restoredMessage;
            connectionStatus.hidden = false;

            connectionStatusTimer = window.setTimeout(() => {
                if (navigator.onLine !== false) {
                    connectionStatus.hidden = true;
                }
            },4000);
        } else {
            connectionStatus.hidden = true;
        }

        connectionWasOffline = offline;
    }

    window.addEventListener('offline',() => updateConnectionStatus());
    window.addEventListener('online',() => updateConnectionStatus(true));

    document.addEventListener('submit',event => {
        if (!(event.target instanceof HTMLFormElement)
            || !event.target.matches('[data-statistics-network-form]')
            || navigator.onLine !== false) {
            return;
        }

        event.preventDefault();
        updateConnectionStatus();
    });

    document.addEventListener('click',event => {
        const drilldownLink = event.target.closest('[data-statistics-drilldown]');

        if (!drilldownLink || navigator.onLine !== false) {
            return;
        }

        event.preventDefault();
        updateConnectionStatus();
    });

    updateConnectionStatus();

    const presetInputs = rangeForm
        ? Array.from(rangeForm.querySelectorAll('input[name="preset"]'))
        : [];

    const startDateInput = document.getElementById('statisticsStartDate');
    const endDateInput = document.getElementById('statisticsEndDate');
    const initialDateDisabled = new WeakMap();

    [startDateInput,endDateInput]
        .filter(Boolean)
        .forEach(input => initialDateDisabled.set(input,input.disabled));

    function selectedPreset() {
        return presetInputs.find(input => input.checked)?.value;
    }

    function updateCustomDateControls() {
        const customRangeSelected = selectedPreset() === 'CUSTOM';

        [startDateInput,endDateInput]
            .filter(Boolean)
            .forEach(input => {
                input.disabled = initialDateDisabled.get(input) || !customRangeSelected;
            });
    }

    presetInputs.forEach(input => {
        input.addEventListener('change',updateCustomDateControls);
    });

    updateCustomDateControls();

    const pageDataElement = document.getElementById('statistics-page-data');

    if (!pageDataElement) {
        return;
    }

    const chartError = document.getElementById('statisticsChartError');
    const chartEmpty = document.getElementById('statisticsChartEmpty');
    const chartWrapper = document.getElementById('statisticsChartWrapper');
    const chartCanvas = document.getElementById('sensorRangeChart');

    function showChartError(error) {
        if (chartWrapper) {
            chartWrapper.hidden = true;
        }

        if (chartEmpty) {
            chartEmpty.hidden = true;
        }

        if (chartError) {
            chartError.hidden = false;
        }

        console.error('Statistics chart could not be initialized.',error);
    }

    let pageData;

    try {
        pageData = JSON.parse(pageDataElement.textContent);
    } catch (error) {
        showChartError(error);
        return;
    }

    const series = pageData?.series;
    const i18n = pageData?.i18n;
    const locale = pageData?.locale || document.documentElement.lang || 'en';

    const supportedSensorTypes = ['TEMPERATURE','HUMIDITY','MOTION'];
    const supportedPointStatuses = [
        'COMPLETE',
        'NO_SAMPLES',
        'PARTIAL',
        'ROLLUP_DELAY',
        'EXPIRED'
    ];
    const supportedGranularities = ['RAW','HOURLY','DAILY','WEEKLY','MONTHLY'];

    function validSeries(candidate) {
        return candidate
            && candidate.sensor
            && candidate.sensor.id !== null
            && typeof candidate.sensor.name === 'string'
            && supportedSensorTypes.includes(candidate.sensor.type)
            && typeof candidate.sensor.timeZoneId === 'string'
            && Array.isArray(candidate.points)
            && candidate.points.every(point =>
                    point
                    && supportedPointStatuses.includes(point.status)
                    && supportedGranularities.includes(point.granularity)
                    && (
                        point.sourceSampleCount === null
                        || (
                            Number.isInteger(point.sourceSampleCount)
                            && point.sourceSampleCount >= 0
                        )
                    )
            )
            && i18n
            && i18n.pointStatus;
    }

    if (!validSeries(series)) {
        showChartError(new Error('The statistics page data has an unexpected shape.'));
        return;
    }

    const sensorTimeZone = series.sensor.timeZoneId;
    const numberFormatters = new Map();

    function numberFormatter(maximumFractionDigits) {
        if (!numberFormatters.has(maximumFractionDigits)) {
            numberFormatters.set(
                maximumFractionDigits,
                new Intl.NumberFormat(locale,{
                    maximumFractionDigits,
                    minimumFractionDigits: 0
                })
            );
        }

        return numberFormatters.get(maximumFractionDigits);
    }

    function finiteNumber(value) {
        if (value === null || value === undefined || value === '') {
            return null;
        }

        const numericValue = Number(value);
        return Number.isFinite(numericValue) ? numericValue : null;
    }

    function formatNumber(value,maximumFractionDigits = 2) {
        const numericValue = finiteNumber(value);

        if (numericValue === null) {
            return '—';
        }

        return numberFormatter(maximumFractionDigits).format(numericValue);
    }

    document.querySelectorAll('[data-statistics-number]').forEach(element => {
        const maximumFractionDigits = Number.parseInt(
            element.dataset.maximumFractionDigits || '2',
            10
        );

        element.textContent = formatNumber(
            element.dataset.statisticsNumber,
            Number.isInteger(maximumFractionDigits) ? maximumFractionDigits : 2
        );
    });

    function createInstantFormatter(compact,includeSeconds) {
        const options = compact
            ? {
                timeZone: sensorTimeZone,
                month: 'short',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit'
            }
            : {
                timeZone: sensorTimeZone,
                year: 'numeric',
                month: 'short',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit'
            };

        if (includeSeconds) {
            options.second = '2-digit';
        }

        try {
            return new Intl.DateTimeFormat(locale,{
                ...options,
                timeZoneName: 'shortOffset'
            });
        } catch (error) {
            return new Intl.DateTimeFormat(locale,{
                ...options,
                timeZoneName: 'short'
            });
        }
    }

    const fullInstantFormatter = createInstantFormatter(false,true);
    const hourlyInstantFormatter = createInstantFormatter(false,false);
    const compactInstantFormatter = createInstantFormatter(true,false);
    const rawLabelFormatter = createInstantFormatter(true,true);
    const localDateFormatter = new Intl.DateTimeFormat(locale,{
        timeZone: 'UTC',
        year: 'numeric',
        month: 'short',
        day: '2-digit'
    });

    function parseInstant(value) {
        const instant = new Date(value);
        return Number.isNaN(instant.getTime()) ? null : instant;
    }

    function formatInstant(value,formatter = fullInstantFormatter) {
        const instant = parseInstant(value);
        return instant ? formatter.format(instant) : String(value ?? '—');
    }

    function parseLocalDate(value) {
        if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
            return null;
        }

        const [year,month,day] = value.split('-').map(Number);
        return new Date(Date.UTC(year,month - 1,day));
    }

    function formatLocalDate(value) {
        const localDate = parseLocalDate(value);
        return localDate ? localDateFormatter.format(localDate) : String(value ?? '—');
    }

    function previousLocalDate(value) {
        const localDate = parseLocalDate(value);

        if (!localDate) {
            return value;
        }

        localDate.setUTCDate(localDate.getUTCDate() - 1);
        return localDate.toISOString().slice(0,10);
    }

    document.querySelectorAll('[data-statistics-instant]').forEach(element => {
        element.textContent = formatInstant(element.getAttribute('datetime'));
    });

    function formatPeriodBoundary(point,boundary) {
        if (point.granularity === 'RAW') {
            return formatInstant(point.recordedAt,fullInstantFormatter);
        }

        const instant = boundary === 'start' ? point.bucketStart : point.bucketEnd;
        const formatter = point.granularity === 'HOURLY'
            ? hourlyInstantFormatter
            : fullInstantFormatter;

        return formatInstant(instant,formatter);
    }

    series.points.forEach((point,index) => {
        const periodElement = document.querySelector(
            `[data-statistics-period][data-point-index="${index}"]`
        );

        if (!periodElement) {
            return;
        }

        const startElement = periodElement.querySelector('[data-statistics-period-start]');
        const endElement = periodElement.querySelector('[data-statistics-period-end]');

        if (startElement) {
            startElement.textContent = formatPeriodBoundary(point,'start');
        }

        if (endElement) {
            endElement.textContent = formatPeriodBoundary(point,'end');
        }
    });

    function formatDuration(secondsValue) {
        const seconds = Math.max(0,Number(secondsValue) || 0);
        let value = seconds;
        let unit = i18n.durationSecond;

        if (seconds >= 86400) {
            value = seconds / 86400;
            unit = i18n.durationDay;
        } else if (seconds >= 3600) {
            value = seconds / 3600;
            unit = i18n.durationHour;
        } else if (seconds >= 60) {
            value = seconds / 60;
            unit = i18n.durationMinute;
        }

        return `${formatNumber(value,1)} ${unit}`;
    }

    document.querySelectorAll('[data-statistics-duration]').forEach(element => {
        element.textContent = formatDuration(element.dataset.statisticsDuration);
    });

    if (!chartCanvas || !chartWrapper || !chartEmpty || !chartError) {
        showChartError(new Error('A required chart element is missing.'));
        return;
    }

    const ChartLibrary = window.Chart;

    if (!ChartLibrary) {
        showChartError(new Error('Chart.js is unavailable.'));
        return;
    }

    const rootStyles = getComputedStyle(document.documentElement);

    function cssColor(variableName,fallback) {
        return rootStyles.getPropertyValue(variableName).trim() || fallback;
    }

    const colors = {
        primary: cssColor('--color-primary','#2f6feb'),
        info: cssColor('--color-info','#22d3ee'),
        success: cssColor('--color-success','#34d399'),
        warning: cssColor('--color-warning','#fbbf24'),
        text: cssColor('--color-text','#f3f4f6'),
        textSoft: cssColor('--color-text-soft','#d1d5db'),
        textMuted: cssColor('--color-text-muted','#aab2be'),
        border: cssColor('--color-border','#374151')
    };

    ChartLibrary.defaults.color = colors.textSoft;
    ChartLibrary.defaults.font.family =
        rootStyles.getPropertyValue('--font-family-sans').trim() || 'sans-serif';
    ChartLibrary.defaults.locale = locale;

    const reducedMotion =
        typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function hasPointData(point) {
        if (!Number.isInteger(point.sourceSampleCount) || point.sourceSampleCount <= 0) {
            return false;
        }

        return series.sensor.type === 'MOTION'
            ? point.motionMetrics !== null
            : point.numericMetrics !== null;
    }

    if (!series.points.some(hasPointData)) {
        chartWrapper.hidden = true;
        chartError.hidden = true;
        chartEmpty.hidden = false;
        return;
    }

    function formatPointLabel(point) {
        if (point.granularity === 'RAW') {
            return formatInstant(point.recordedAt,rawLabelFormatter);
        }

        if (point.granularity === 'HOURLY' || !point.localDateStart) {
            return formatInstant(point.bucketStart,compactInstantFormatter);
        }

        if (point.granularity === 'DAILY') {
            return formatLocalDate(point.localDateStart);
        }

        return [
            formatLocalDate(point.localDateStart),
            formatLocalDate(previousLocalDate(point.localDateEndExclusive))
        ];
    }

    function formatPointPeriod(point) {
        if (point.granularity === 'RAW') {
            return formatInstant(point.recordedAt,fullInstantFormatter);
        }

        if (point.localDateStart && point.localDateEndExclusive) {
            return `${formatLocalDate(point.localDateStart)} → `
                + `${formatLocalDate(point.localDateEndExclusive)} (${i18n.endExclusive})`;
        }

        return `${formatInstant(point.bucketStart,hourlyInstantFormatter)} → `
            + `${formatInstant(point.bucketEnd,hourlyInstantFormatter)}`;
    }

    function displayUnitSuffix() {
        return series.sensor.displayUnitSymbol
            ? ` ${series.sensor.displayUnitSymbol}`
            : '';
    }

    function pointAccent(point,normalColor) {
        return point.status === 'PARTIAL' ? colors.warning : normalColor;
    }

    function rowDrilldownLink(index) {
        return document.querySelector(
            `[data-statistics-point-row][data-point-index="${index}"] `
            + '[data-statistics-drilldown]'
        );
    }

    const labels = series.points.map(formatPointLabel);

    const tooltip = {
        backgroundColor: 'rgba(17, 24, 39, 0.96)',
        titleColor: colors.text,
        bodyColor: colors.textSoft,
        borderColor: colors.border,
        borderWidth: 1,
        padding: 12,
        boxPadding: 4,

        callbacks: {
            title(items) {
                const item = items[0];
                return item ? formatPointPeriod(series.points[item.dataIndex]) : '';
            },

            label(context) {
                const metric = context.dataset.statisticsMetric;

                if (metric === 'range') {
                    const value = context.raw;

                    return `${i18n.chartRange}: ${formatNumber(value[0],2)}`
                        + ` – ${formatNumber(value[1],2)}${displayUnitSuffix()}`;
                }

                if (metric === 'average') {
                    return `${i18n.chartAverage}: `
                        + `${formatNumber(context.raw,2)}${displayUnitSuffix()}`;
                }

                if (metric === 'trueSamples') {
                    return `${i18n.chartTrueSamples}: ${formatNumber(context.raw,0)}`;
                }

                if (metric === 'falseSamples') {
                    return `${i18n.chartFalseSamples}: ${formatNumber(context.raw,0)}`;
                }

                if (metric === 'truePercentage') {
                    return `${i18n.chartTruePercentage}: ${formatNumber(context.raw,1)}%`;
                }

                return String(context.formattedValue);
            },

            afterBody(items) {
                const item = items[0];

                if (!item) {
                    return [];
                }

                const point = series.points[item.dataIndex];

                return [
                    `${i18n.statusLabel}: ${i18n.pointStatus[point.status]}`,
                    `${i18n.samples}: ${formatNumber(point.sourceSampleCount,0)}`
                ];
            }
        }
    };

    function baseChartOptions(scales) {
        return {
            responsive: true,
            maintainAspectRatio: false,
            animation: reducedMotion ? false : {duration: 300},

            interaction: {
                mode: 'index',
                intersect: false
            },

            onClick(event,elements) {
                const selectedElement = elements[0];

                if (!selectedElement) {
                    return;
                }

                const link = rowDrilldownLink(selectedElement.index);

                if (!link) {
                    return;
                }

                if (navigator.onLine === false) {
                    updateConnectionStatus();
                    return;
                }

                window.location.assign(link.href);
            },

            onHover(event,elements) {
                const selectedElement = elements[0];
                const hasDrilldown =
                    selectedElement && rowDrilldownLink(selectedElement.index);

                event.native.target.style.cursor = hasDrilldown ? 'pointer' : 'default';
            },

            plugins: {
                legend: {
                    position: 'top',

                    labels: {
                        color: colors.textSoft,
                        usePointStyle: true
                    }
                },

                tooltip
            },

            scales
        };
    }

    function numericChartConfiguration() {
        const averages = series.points.map(point => {
            if (!hasPointData(point) || !point.numericMetrics) {
                return null;
            }

            return finiteNumber(point.numericMetrics.average);
        });

        const ranges = series.points.map(point => {
            if (!hasPointData(point) || !point.numericMetrics) {
                return null;
            }

            const minimum = finiteNumber(point.numericMetrics.minimum);
            const maximum = finiteNumber(point.numericMetrics.maximum);

            return minimum === null || maximum === null
                ? null
                : [minimum,maximum];
        });

        const averageColors = series.points.map(point => pointAccent(point,colors.info));
        const rangeColors = series.points.map(point =>
            point.status === 'PARTIAL'
                ? 'rgba(251, 191, 36, 0.24)'
                : 'rgba(47, 111, 235, 0.22)'
        );

        const axisLabel = series.sensor.displayUnitSymbol
            ? `${i18n.chartAxis[series.sensor.type]} (${series.sensor.displayUnitSymbol})`
            : i18n.chartAxis[series.sensor.type];

        const yScale = {
            beginAtZero: series.sensor.type !== 'TEMPERATURE',

            grid: {
                color: 'rgba(148, 163, 184, 0.16)'
            },

            ticks: {
                color: colors.textSoft
            },

            title: {
                display: true,
                color: colors.textMuted,
                text: axisLabel
            }
        };

        if (series.sensor.type === 'HUMIDITY') {
            yScale.min = 0;
            yScale.max = 100;
        }

        return {
            type: 'bar',

            data: {
                labels,

                datasets: [
                    {
                        type: 'bar',
                        statisticsMetric: 'range',
                        label: i18n.chartRange,
                        data: ranges,
                        backgroundColor: rangeColors,
                        borderColor: averageColors,
                        borderWidth: 1,
                        borderSkipped: false,
                        borderRadius: 4,
                        barPercentage: 0.75,
                        categoryPercentage: 0.9,
                        order: 2
                    },
                    {
                        type: 'line',
                        statisticsMetric: 'average',
                        label: i18n.chartAverage,
                        data: averages,
                        borderColor: colors.info,
                        backgroundColor: colors.info,
                        pointBackgroundColor: averageColors,
                        pointBorderColor: averageColors,
                        pointRadius: 3,
                        pointHoverRadius: 6,
                        borderWidth: 2,
                        tension: 0.2,
                        spanGaps: false,
                        fill: false,
                        order: 1
                    }
                ]
            },

            options: baseChartOptions({
                x: {
                    grid: {
                        display: false
                    },

                    ticks: {
                        color: colors.textMuted,
                        maxRotation: 0,
                        autoSkip: true
                    }
                },

                y: yScale
            })
        };
    }

    function motionChartConfiguration() {
        const trueSamples = series.points.map(point =>
            hasPointData(point)
                ? finiteNumber(point.motionMetrics.trueSampleCount)
                : null
        );

        const falseSamples = series.points.map(point =>
            hasPointData(point)
                ? finiteNumber(point.motionMetrics.falseSampleCount)
                : null
        );

        const percentages = series.points.map(point =>
            hasPointData(point)
                ? finiteNumber(point.motionMetrics.truePercentage)
                : null
        );

        const trueColors = series.points.map(point =>
            point.status === 'PARTIAL'
                ? 'rgba(251, 191, 36, 0.82)'
                : 'rgba(52, 211, 153, 0.78)'
        );

        const falseColors = series.points.map(point =>
            point.status === 'PARTIAL'
                ? 'rgba(251, 191, 36, 0.32)'
                : 'rgba(107, 114, 128, 0.62)'
        );

        return {
            type: 'bar',

            data: {
                labels,

                datasets: [
                    {
                        statisticsMetric: 'trueSamples',
                        label: i18n.chartTrueSamples,
                        data: trueSamples,
                        backgroundColor: trueColors,
                        borderColor: colors.success,
                        borderWidth: 1,
                        borderRadius: 4,
                        stack: 'samples',
                        order: 2
                    },
                    {
                        statisticsMetric: 'falseSamples',
                        label: i18n.chartFalseSamples,
                        data: falseSamples,
                        backgroundColor: falseColors,
                        borderColor: colors.textMuted,
                        borderWidth: 1,
                        borderRadius: 4,
                        stack: 'samples',
                        order: 2
                    },
                    {
                        type: 'line',
                        statisticsMetric: 'truePercentage',
                        label: i18n.chartTruePercentage,
                        data: percentages,
                        yAxisID: 'percentage',
                        borderColor: colors.info,
                        backgroundColor: colors.info,
                        pointBackgroundColor: series.points.map(point =>
                            pointAccent(point,colors.info)
                        ),
                        pointRadius: 3,
                        pointHoverRadius: 6,
                        borderWidth: 2,
                        tension: 0.2,
                        spanGaps: false,
                        fill: false,
                        order: 1
                    }
                ]
            },

            options: baseChartOptions({
                x: {
                    stacked: true,

                    grid: {
                        display: false
                    },

                    ticks: {
                        color: colors.textMuted,
                        maxRotation: 0,
                        autoSkip: true
                    }
                },

                y: {
                    stacked: true,
                    beginAtZero: true,

                    grid: {
                        color: 'rgba(148, 163, 184, 0.16)'
                    },

                    ticks: {
                        color: colors.textSoft,
                        precision: 0
                    },

                    title: {
                        display: true,
                        color: colors.textMuted,
                        text: i18n.chartAxisSamples
                    }
                },

                percentage: {
                    position: 'right',
                    min: 0,
                    max: 100,

                    grid: {
                        drawOnChartArea: false
                    },

                    ticks: {
                        color: colors.info,
                        callback(value) {
                            return `${value}%`;
                        }
                    },

                    title: {
                        display: true,
                        color: colors.info,
                        text: i18n.chartAxisPercentage
                    }
                }
            })
        };
    }

    try {
        const configuration = series.sensor.type === 'MOTION'
            ? motionChartConfiguration()
            : numericChartConfiguration();

        new ChartLibrary(chartCanvas.getContext('2d'),configuration);

        chartError.hidden = true;
        chartEmpty.hidden = true;
        chartWrapper.hidden = false;
    } catch (error) {
        showChartError(error);
    }
})();