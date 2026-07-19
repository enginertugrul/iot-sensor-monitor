(() => {
    const connectionStatus = document.getElementById(
        'statisticsConnectionStatus'
    );

    const connectionMessage = document.getElementById(
        'statisticsConnectionMessage'
    );

    const sensorSelect = document.getElementById('sensorId');

    const sensorSelectInitiallyDisabled =
        sensorSelect?.disabled ?? false;

    let connectionStatusTimer = null;
    let connectionWasOffline =
        navigator.onLine === false;

    function updatePageConnectionStatus(
        announceRestoration = false
    ) {
        const offline = navigator.onLine === false;

        if (connectionStatus && connectionMessage) {
            window.clearTimeout(connectionStatusTimer);

            if (offline) {
                connectionStatus.dataset.state = 'offline';

                connectionMessage.textContent =
                    connectionStatus.dataset.offlineMessage;

                connectionStatus.hidden = false;
            } else if (
                announceRestoration
                && connectionWasOffline
            ) {
                connectionStatus.dataset.state = 'restored';

                connectionMessage.textContent =
                    connectionStatus.dataset.restoredMessage;

                connectionStatus.hidden = false;

                connectionStatusTimer = window.setTimeout(
                    () => {
                        if (navigator.onLine !== false) {
                            connectionStatus.hidden = true;
                        }
                    },
                    4000
                );
            } else {
                connectionStatus.hidden = true;
            }
        }

        if (sensorSelect) {
            sensorSelect.disabled =
                sensorSelectInitiallyDisabled || offline;
        }

        connectionWasOffline = offline;
    }

    window.addEventListener(
        'offline',
        () => updatePageConnectionStatus()
    );

    window.addEventListener(
        'online',
        () => updatePageConnectionStatus(true)
    );

    updatePageConnectionStatus();

    const pageDataElement = document.getElementById(
        'statistics-page-data'
    );

    /*
     * This script is intentionally loaded without a selected
     * sensor so the page-level connection status still works.
     */
    if (!pageDataElement) {
        return;
    }

    const chartError = document.getElementById(
        'statisticsChartError'
    );

    const charts = document.getElementById(
        'statisticsCharts'
    );

    const hourlyChartRegion = document.getElementById(
        'hourlyChartRegion'
    );

    const hourlyRequestStatus = document.getElementById(
        'hourlyRequestStatus'
    );

    const hourlyRequestMessage = document.getElementById(
        'hourlyRequestMessage'
    );

    const hourlyRetryButton = document.getElementById(
        'hourlyRetryButton'
    );

    const hourlyEmptyState = document.getElementById(
        'hourlyEmptyState'
    );

    const hourlyChartCanvasWrapper =
        document.getElementById(
            'hourlyChartCanvasWrapper'
        );

    const hourlyCanvas = document.getElementById(
        'sensorHourlyChart'
    );

    const weeklyEmptyState = document.getElementById(
        'weeklyEmptyState'
    );

    const weeklyChartCanvasWrapper =
        document.getElementById(
            'weeklyChartCanvasWrapper'
        );

    const weeklyCanvas = document.getElementById(
        'sensorWeeklyChart'
    );

    const hourlyChartTitle = document.getElementById(
        'hourlyChartTitle'
    );

    const previousDayButton = document.getElementById(
        'prevDayBtn'
    );

    const nextDayButton = document.getElementById(
        'nextDayBtn'
    );

    let weeklyChart = null;
    let hourlyChart = null;

    function showFatalChartError(error) {
        weeklyChart?.destroy();
        hourlyChart?.destroy();

        if (charts) {
            charts.hidden = true;
        }

        if (chartError) {
            chartError.hidden = false;
        }

        if (hourlyChartRegion) {
            hourlyChartRegion.setAttribute(
                'aria-busy',
                'false'
            );
        }

        if (previousDayButton) {
            previousDayButton.disabled = true;
        }

        if (nextDayButton) {
            nextDayButton.disabled = true;
        }

        if (hourlyRetryButton) {
            hourlyRetryButton.hidden = true;
        }

        console.error(
            'Statistics charts could not be initialized.',
            error
        );
    }

    const requiredElements = [
        chartError,
        charts,
        hourlyChartRegion,
        hourlyRequestStatus,
        hourlyRequestMessage,
        hourlyRetryButton,
        hourlyEmptyState,
        hourlyChartCanvasWrapper,
        hourlyCanvas,
        weeklyEmptyState,
        weeklyChartCanvasWrapper,
        weeklyCanvas,
        hourlyChartTitle,
        previousDayButton,
        nextDayButton
    ];

    if (requiredElements.some(element => !element)) {
        showFatalChartError(
            new Error(
                'A required statistics page element is missing.'
            )
        );

        return;
    }

    let pageData;

    try {
        pageData = JSON.parse(
            pageDataElement.textContent
        );
    } catch (error) {
        showFatalChartError(error);
        return;
    }

    const {
        selectedSensorId,
        selectedSensorName,
        sensorType,
        measurementUnitSymbol,
        weeklyData,
        hourlyData,
        today,
        i18n
    } = pageData;

    function isNullableFiniteNumber(value) {
        return value === null
            || (
                typeof value === 'number'
                && Number.isFinite(value)
            );
    }

    function isHourlySeries(data) {
        return Array.isArray(data)
            && data.length === 24
            && data.every((item, index) =>
                item
                && Number.isInteger(item.hour)
                && item.hour === index
                && isNullableFiniteNumber(item.value)
            );
    }

    function isWeeklySeries(data) {
        return Array.isArray(data)
            && data.length === 7
            && data.every(item =>
                item
                && typeof item.date === 'string'
                && /^\d{4}-\d{2}-\d{2}$/.test(item.date)
                && isNullableFiniteNumber(item.value)
            );
    }

    function hasRequiredMessages(messages) {
        if (!messages) {
            return false;
        }

        const requiredMessages = [
            'tooltipAverage',
            'tooltipDetections',
            'noDataRecorded',
            'todayHourly',
            'hourlyStatistics',
            'hourAxis',
            'loadingHourly',
            'hourlyLoaded',
            'hourlyLoadError',
            'hourlyOffline',
            'connectionRestoredRetry'
        ];

        return requiredMessages.every(key =>
                typeof messages[key] === 'string'
            )
            && Array.isArray(messages.days)
            && messages.days.length === 7
            && messages.days.every(day =>
                typeof day === 'string'
            )
            && typeof messages.dailyDataset?.[sensorType]
            === 'string'
            && typeof messages.hourlyDataset?.[sensorType]
            === 'string'
            && typeof messages.axis?.[sensorType]
            === 'string';
    }

    const supportedSensorTypes = [
        'TEMPERATURE',
        'HUMIDITY',
        'MOTION'
    ];

    const pageDataIsValid =
        selectedSensorId !== null
        && selectedSensorId !== undefined
        && typeof selectedSensorName === 'string'
        && supportedSensorTypes.includes(sensorType)
        && typeof measurementUnitSymbol === 'string'
        && typeof today === 'string'
        && /^\d{4}-\d{2}-\d{2}$/.test(today)
        && isWeeklySeries(weeklyData)
        && isHourlySeries(hourlyData)
        && hasRequiredMessages(i18n);

    if (!pageDataIsValid) {
        showFatalChartError(
            new Error(
                'The statistics page data has an unexpected shape.'
            )
        );

        return;
    }

    const ChartLibrary = window.Chart;

    if (!ChartLibrary) {
        showFatalChartError(
            new Error('Chart.js is unavailable.')
        );

        return;
    }

    ChartLibrary.defaults.color = '#9ca3af';

    ChartLibrary.defaults.font.family =
        '-apple-system, BlinkMacSystemFont, '
        + '"Segoe UI", Roboto, Helvetica, Arial, sans-serif';

    const motionSensor = sensorType === 'MOTION';

    const darkTooltip = {
        backgroundColor: 'rgba(17, 24, 39, 0.94)',
        titleColor: '#f3f4f6',
        bodyColor: '#d1d5db',
        borderColor: '#374151',
        borderWidth: 1,
        padding: 10,
        boxPadding: 4
    };

    function labelWithUnit(label) {
        return measurementUnitSymbol
            ? `${label} (${measurementUnitSymbol})`
            : label;
    }

    function formatMetricValue(value) {
        if (value === null || value === undefined) {
            return i18n.noDataRecorded;
        }

        if (motionSensor) {
            return `${i18n.tooltipDetections}: `
                + Math.round(value);
        }

        const unit = measurementUnitSymbol
            ? ` ${measurementUnitSymbol}`
            : '';

        return `${i18n.tooltipAverage}: `
            + Number(value).toFixed(2)
            + unit;
    }

    function createYAxis() {
        const scale = {
            beginAtZero: sensorType !== 'TEMPERATURE',

            grid: {
                color: 'rgba(148, 163, 184, 0.16)'
            },

            title: {
                display: true,
                text: labelWithUnit(
                    i18n.axis[sensorType]
                ),
                color: '#9ca3af'
            },

            ticks: {
                color: '#d1d5db'
            }
        };

        if (sensorType === 'HUMIDITY') {
            scale.min = 0;
            scale.max = 100;
        }

        if (motionSensor) {
            scale.ticks.precision = 0;
        }

        return scale;
    }

    /*
     * Do not use new Date('YYYY-MM-DD'). Browsers parse that
     * form as UTC, which can display a different local date.
     */
    function parseDateString(value) {
        const [year, month, day] =
            value.split('-').map(Number);

        return new Date(year, month - 1, day);
    }

    function formatDateForApi(date) {
        const year = date.getFullYear();

        const month = String(
            date.getMonth() + 1
        ).padStart(2, '0');

        const day = String(
            date.getDate()
        ).padStart(2, '0');

        return `${year}-${month}-${day}`;
    }

    function hasDisplayableData(data) {
        if (data.length === 0) {
            return false;
        }

        if (motionSensor) {
            return true;
        }

        return data.some(item =>
            item.value !== null
            && item.value !== undefined
        );
    }

    function toHourlyChartData(data) {
        return {
            labels: data.map(item =>
                `${String(item.hour).padStart(2, '0')}:00`
            ),

            values: data.map(item => item.value)
        };
    }

    function createWeeklyChart(data) {
        const labels = [];
        const values = [];

        data.forEach(item => {
            const date = parseDateString(item.date);
            const dayName = i18n.days[date.getDay()];

            const day = String(
                date.getDate()
            ).padStart(2, '0');

            const month = String(
                date.getMonth() + 1
            ).padStart(2, '0');

            labels.push([
                dayName,
                `${day}-${month}-${date.getFullYear()}`
            ]);

            values.push(item.value);
        });

        return new ChartLibrary(
            weeklyCanvas.getContext('2d'),
            {
                type: 'bar',

                data: {
                    labels,

                    datasets: [{
                        label: labelWithUnit(
                            i18n.dailyDataset[sensorType]
                        ),

                        data: values,

                        backgroundColor: context =>
                            context.dataIndex
                            === values.length - 1
                                ? 'rgba(245, 158, 11, 0.85)'
                                : 'rgba(59, 130, 246, 0.70)',

                        borderColor: context =>
                            context.dataIndex
                            === values.length - 1
                                ? 'rgba(245, 158, 11, 1)'
                                : 'rgba(59, 130, 246, 1)',

                        borderWidth: 1,
                        borderRadius: 6
                    }]
                },

                options: {
                    responsive: true,
                    maintainAspectRatio: false,

                    plugins: {
                        legend: {
                            display: true,
                            position: 'top',

                            labels: {
                                color: '#e5e7eb'
                            }
                        },

                        tooltip: {
                            ...darkTooltip,

                            callbacks: {
                                label: context =>
                                    formatMetricValue(
                                        context.parsed.y
                                    )
                            }
                        }
                    },

                    scales: {
                        y: createYAxis(),

                        x: {
                            grid: {
                                display: false
                            },

                            ticks: {
                                color: context =>
                                    context.index
                                    === values.length - 1
                                        ? 'rgba(245, 158, 11, 1)'
                                        : '#9ca3af'
                            }
                        }
                    }
                }
            }
        );
    }

    function createHourlyChart(data) {
        const chartData = toHourlyChartData(data);

        return new ChartLibrary(
            hourlyCanvas.getContext('2d'),
            {
                type: motionSensor ? 'bar' : 'line',

                data: {
                    labels: chartData.labels,

                    datasets: [{
                        label: labelWithUnit(
                            i18n.hourlyDataset[sensorType]
                        ),

                        data: chartData.values,

                        backgroundColor:
                            'rgba(6, 182, 212, 0.20)',

                        borderColor:
                            'rgba(6, 182, 212, 1)',

                        borderWidth: 2,
                        pointRadius: motionSensor ? 0 : 4,

                        pointHoverRadius:
                            motionSensor ? 0 : 6,

                        fill: !motionSensor,
                        tension: 0.3,
                        spanGaps: false
                    }]
                },

                options: {
                    responsive: true,
                    maintainAspectRatio: false,

                    plugins: {
                        legend: {
                            display: true,
                            position: 'top',

                            labels: {
                                color: '#e5e7eb'
                            }
                        },

                        tooltip: {
                            ...darkTooltip,

                            callbacks: {
                                title: tooltipItems => {
                                    const currentHour =
                                        Number.parseInt(
                                            tooltipItems[0]
                                                .label
                                                .split(':')[0],
                                            10
                                        );

                                    const nextHour = String(
                                        (currentHour + 1) % 24
                                    ).padStart(2, '0');

                                    return `${tooltipItems[0].label}`
                                        + ` - ${nextHour}:00`;
                                },

                                label: context =>
                                    formatMetricValue(
                                        context.parsed.y
                                    )
                            }
                        }
                    },

                    scales: {
                        y: createYAxis(),

                        x: {
                            grid: {
                                color:
                                    'rgba(148, 163, 184, 0.10)'
                            },

                            title: {
                                display: true,
                                text: i18n.hourAxis,
                                color: '#9ca3af'
                            },

                            ticks: {
                                color: '#d1d5db'
                            }
                        }
                    }
                }
            }
        );
    }

    function renderWeeklyData(data) {
        const hasData = hasDisplayableData(data);

        weeklyEmptyState.hidden = hasData;
        weeklyChartCanvasWrapper.hidden = !hasData;

        if (!hasData) {
            return;
        }

        if (!weeklyChart) {
            weeklyChart = createWeeklyChart(data);
            return;
        }

        window.requestAnimationFrame(
            () => weeklyChart?.resize()
        );
    }

    function renderHourlyData(data) {
        const hasData = hasDisplayableData(data);

        hourlyEmptyState.hidden = hasData;
        hourlyChartCanvasWrapper.hidden = !hasData;

        if (!hasData) {
            return false;
        }

        const chartData = toHourlyChartData(data);

        if (!hourlyChart) {
            hourlyChart = createHourlyChart(data);
            return true;
        }

        hourlyChart.data.labels = chartData.labels;

        hourlyChart.data.datasets[0].data =
            chartData.values;

        hourlyChart.update();

        window.requestAnimationFrame(
            () => hourlyChart?.resize()
        );

        return true;
    }

    const todayDate = parseDateString(today);

    if (Number.isNaN(todayDate.getTime())) {
        showFatalChartError(
            new Error('The statistics date is invalid.')
        );

        return;
    }

    let currentDate = new Date(todayDate);
    let failedTargetDate = null;
    let requestInProgress = false;
    let hourlyStatusTimer = null;

    function updateChartTitle(date) {
        const sensorTitle =
            `${selectedSensorName} — `;

        if (
            formatDateForApi(date)
            === formatDateForApi(todayDate)
        ) {
            hourlyChartTitle.textContent =
                sensorTitle + i18n.todayHourly;

            return;
        }

        const dayName = i18n.days[date.getDay()];

        const day = String(
            date.getDate()
        ).padStart(2, '0');

        const month = String(
            date.getMonth() + 1
        ).padStart(2, '0');

        hourlyChartTitle.textContent =
            sensorTitle
            + `${dayName}, `
            + `${day}-${month}-${date.getFullYear()} — `
            + i18n.hourlyStatistics;
    }

    function updateNavigationState() {
        const offline = navigator.onLine === false;

        previousDayButton.disabled =
            requestInProgress || offline;

        nextDayButton.disabled =
            requestInProgress
            || offline
            || (
                formatDateForApi(currentDate)
                >= formatDateForApi(todayDate)
            );

        hourlyRetryButton.disabled =
            requestInProgress
            || offline
            || failedTargetDate === null;
    }

    function showHourlyRequestState(
        state,
        message,
        retryAvailable = false
    ) {
        window.clearTimeout(hourlyStatusTimer);

        hourlyRequestStatus.dataset.state = state;

        hourlyRequestStatus.setAttribute(
            'aria-live',
            state === 'error' || state === 'offline'
                ? 'assertive'
                : 'polite'
        );

        hourlyRequestMessage.textContent = message;
        hourlyRetryButton.hidden = !retryAvailable;
        hourlyRequestStatus.hidden = false;

        updateNavigationState();
    }

    function hideHourlyRequestState() {
        window.clearTimeout(hourlyStatusTimer);

        hourlyRequestStatus.hidden = true;
        hourlyRetryButton.hidden = true;
    }

    function showHourlySuccess() {
        showHourlyRequestState(
            'success',
            i18n.hourlyLoaded
        );

        hourlyStatusTimer = window.setTimeout(
            () => {
                if (
                    hourlyRequestStatus.dataset.state
                    === 'success'
                ) {
                    hideHourlyRequestState();
                }
            },
            2500
        );
    }

    function setRequestInProgress(loading) {
        requestInProgress = loading;

        hourlyChartRegion.setAttribute(
            'aria-busy',
            loading ? 'true' : 'false'
        );

        updateNavigationState();
    }

    function isHourlyResponse(data) {
        return isHourlySeries(data);
    }

    async function fetchHourlyData(date) {
        const parameters = new URLSearchParams({
            date: formatDateForApi(date)
        });

        const response = await fetch(
            '/api/sensors/'
            + encodeURIComponent(selectedSensorId)
            + '/statistics/hourly?'
            + parameters.toString(),
            {
                credentials: 'same-origin',
                cache: 'no-store',

                headers: {
                    'Accept': 'application/json'
                }
            }
        );

        if (response.redirected || !response.ok) {
            throw new Error(
                'Hourly statistics request failed with HTTP '
                + response.status
                + '.'
            );
        }

        const contentType =
            response.headers.get('content-type') ?? '';

        if (
            !contentType
                .toLowerCase()
                .includes('application/json')
        ) {
            throw new Error(
                'Hourly statistics returned a non-JSON response.'
            );
        }

        const data = await response.json();

        if (!isHourlyResponse(data)) {
            throw new Error(
                'Hourly statistics returned an unexpected shape.'
            );
        }

        return data;
    }

    async function loadHourlyDate(targetDate) {
        if (requestInProgress) {
            return;
        }

        const requestedDate = new Date(
            targetDate.getTime()
        );

        if (navigator.onLine === false) {
            failedTargetDate = requestedDate;

            showHourlyRequestState(
                'offline',
                i18n.hourlyOffline,
                true
            );

            return;
        }

        failedTargetDate = null;
        setRequestInProgress(true);

        showHourlyRequestState(
            'loading',
            i18n.loadingHourly
        );

        try {
            const data = await fetchHourlyData(
                requestedDate
            );

            const hasData = renderHourlyData(data);

            currentDate = requestedDate;
            failedTargetDate = null;

            updateChartTitle(currentDate);

            if (hasData) {
                showHourlySuccess();
            } else {
                hideHourlyRequestState();
            }
        } catch (error) {
            failedTargetDate = requestedDate;

            if (navigator.onLine === false) {
                showHourlyRequestState(
                    'offline',
                    i18n.hourlyOffline,
                    true
                );
            } else {
                showHourlyRequestState(
                    'error',
                    i18n.hourlyLoadError,
                    true
                );
            }

            console.error(
                'Hourly statistics could not be loaded.',
                error
            );
        } finally {
            setRequestInProgress(false);
        }
    }

    function navigateDate(direction) {
        if (
            requestInProgress
            || navigator.onLine === false
        ) {
            return;
        }

        if (
            direction === 'next'
            && formatDateForApi(currentDate)
            >= formatDateForApi(todayDate)
        ) {
            return;
        }

        const targetDate = new Date(
            currentDate.getTime()
        );

        targetDate.setDate(
            targetDate.getDate()
            + (direction === 'next' ? 1 : -1)
        );

        void loadHourlyDate(targetDate);
    }

    function updateChartConnectionState() {
        updateNavigationState();

        if (
            navigator.onLine !== false
            && failedTargetDate
            && hourlyRequestStatus.dataset.state
            === 'offline'
        ) {
            showHourlyRequestState(
                'restored',
                i18n.connectionRestoredRetry,
                true
            );
        }
    }

    previousDayButton.addEventListener(
        'click',
        () => navigateDate('previous')
    );

    nextDayButton.addEventListener(
        'click',
        () => navigateDate('next')
    );

    hourlyRetryButton.addEventListener(
        'click',
        () => {
            if (failedTargetDate) {
                void loadHourlyDate(
                    failedTargetDate
                );
            }
        }
    );

    window.addEventListener(
        'offline',
        updateChartConnectionState
    );

    window.addEventListener(
        'online',
        updateChartConnectionState
    );

    charts.hidden = false;

    try {
        renderWeeklyData(weeklyData);
        renderHourlyData(hourlyData);
    } catch (error) {
        showFatalChartError(error);
        return;
    }

    updateChartTitle(currentDate);
    updateNavigationState();
})();