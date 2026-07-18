(() => {
    const pageDataElement = document.getElementById(
        'statistics-page-data'
    );

    if (!pageDataElement) {
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
    } = JSON.parse(pageDataElement.textContent);

    Chart.defaults.color = '#9ca3af';

    Chart.defaults.font.family =
        '-apple-system, BlinkMacSystemFont, '
        + '"Segoe UI", Roboto, Helvetica, Arial, sans-serif';

    const motionSensor = sensorType === 'MOTION';

    const darkTooltip = {
        backgroundColor: 'rgba(17, 24, 39, 0.9)',
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

        return `${i18n.tooltipAverage}: `
            + `${Number(value).toFixed(2)} `
            + measurementUnitSymbol;
    }

    function createYAxis() {
        const scale = {
            beginAtZero: sensorType !== 'TEMPERATURE',

            grid: {
                color: 'rgba(55, 65, 81, 0.5)'
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
     * Do not use new Date("YYYY-MM-DD"). Browsers parse that
     * form as UTC, which can display the previous day in some
     * local timezones.
     */
    function parseDateString(value) {

        const [year, month, day] = value.split('-').map(Number);

        return new Date(year, month - 1, day);
    }

    function formatDateForApi(date) {
        const year = date.getFullYear();

        const month = String(date.getMonth() + 1).padStart(2, '0');

        const day = String(date.getDate()).padStart(2, '0');

        return `${year}-${month}-${day}`;
    }

    const weeklyLabels = [];
    const weeklyValues = [];

    weeklyData.forEach(item => {
        const date = parseDateString(item.date);
        const dayName = i18n.days[date.getDay()];

        const day = String(date.getDate()).padStart(2, '0');

        const month = String(date.getMonth() + 1).padStart(2, '0');

        const year = date.getFullYear();

        weeklyLabels.push([ dayName,
            `${day}-${month}-${year}` ]);

        weeklyValues.push(item.value);
    });

    const weeklyContext = document
        .getElementById('sensorWeeklyChart')
        .getContext('2d');

    new Chart(weeklyContext, {
        type: 'bar',

        data: {
            labels: weeklyLabels,

            datasets: [{

                label: labelWithUnit(i18n.dailyDataset[sensorType]),

                data: weeklyValues,

                backgroundColor: context =>
                    context.dataIndex === weeklyValues.length - 1
                        ? 'rgba(245, 158, 11, 0.85)'
                        : 'rgba(59, 130, 246, 0.7)',

                borderColor: context =>
                    context.dataIndex === weeklyValues.length - 1
                        ? 'rgba(245, 158, 11, 1)'
                        : 'rgba(59, 130, 246, 1)',

                borderWidth: 1,
                borderRadius: 4
            }]
        },

        options: {
            responsive: true,

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
                            context.index === weeklyValues.length - 1
                                ? 'rgba(245, 158, 11, 1)'
                                : '#9ca3af'
                    }
                }
            }
        }
    });

    function toHourlyChartData(data) {
        return {
            labels: data.map(item =>
                `${String(item.hour)
                    .padStart(2, '0')}:00`
            ),

            values: data.map(item => item.value)
        };
    }

    const initialHourlyData =
        toHourlyChartData(hourlyData);

    const hourlyContext = document
        .getElementById('sensorHourlyChart')
        .getContext('2d');

    const hourlyChart = new Chart(
        hourlyContext,
        {
            type: motionSensor ? 'bar' : 'line',

            data: {
                labels: initialHourlyData.labels,

                datasets: [{
                    label: labelWithUnit(
                        i18n.hourlyDataset[sensorType]
                    ),

                    data: initialHourlyData.values,

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

                                const nextHour =
                                    String((currentHour + 1) % 24).padStart(2, '0');

                                return `${tooltipItems[0].label}`
                                    + ` - ${nextHour}:00`;
                            },

                            label: context =>
                                formatMetricValue(context.parsed.y)
                        }
                    }
                },

                scales: {
                    y: createYAxis(),

                    x: {
                        grid: {
                            color:
                                'rgba(55, 65, 81, 0.2)'
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

    const todayDate = parseDateString(today);

    let currentDate = new Date(todayDate);

    const hourlyChartTitle = document
        .getElementById('hourlyChartTitle');

    const previousDayButton = document
        .getElementById('prevDayBtn');

    const nextDayButton = document
        .getElementById('nextDayBtn');

    function updateChartTitle(date) {
        if (formatDateForApi(date)
            === formatDateForApi(todayDate)) {
            hourlyChartTitle.textContent = `${selectedSensorName} — ` + i18n.todayHourly;

            return;
        }

        const dayName = i18n.days[date.getDay()];

        const day = String(date.getDate() ).padStart(2, '0');

        const month = String(date.getMonth() + 1).padStart(2, '0');

        hourlyChartTitle.textContent =
            `${selectedSensorName} — `
            + `${dayName}, `
            + `${day}-${month}-${date.getFullYear()} — `
            + i18n.hourlyStatistics;
    }

    function updateNavigationState(loading = false) {
        previousDayButton.disabled = loading;

        nextDayButton.disabled = loading || formatDateForApi(currentDate) >= formatDateForApi(todayDate);
    }

    async function fetchHourlyData(date) {
        const parameters = new URLSearchParams({
            date: formatDateForApi(date)
        });

        const response = await fetch(
            `/api/sensors/${
                encodeURIComponent(selectedSensorId)
            }/statistics/hourly?${
                parameters.toString()
            }`
        );

        if (!response.ok) {
            throw new Error( `Failed to load hourly statistics ` + `(HTTP ${response.status})`);
        }

        return response.json();
    }

    function applyHourlyData(data, date) {

        const chartData = toHourlyChartData(data);

        hourlyChart.data.labels = chartData.labels;

        hourlyChart.data.datasets[0].data = chartData.values;

        hourlyChart.update();
        updateChartTitle(date);
    }

    async function navigateDate(direction) {

        if (direction === 'next' && nextDayButton.disabled) {
            return;
        }

        const targetDate = new Date(currentDate);

        targetDate.setDate( targetDate.getDate() + (direction === 'next' ? 1 : -1) );

        updateNavigationState(true);

        try {
            const data = await fetchHourlyData(targetDate);
            currentDate = targetDate;
            applyHourlyData(data, currentDate );
        } catch (error) {
            console.error(error);
        } finally {
            updateNavigationState(false);
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

    updateNavigationState();
})();