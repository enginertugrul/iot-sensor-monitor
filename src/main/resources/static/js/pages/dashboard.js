(() => {
    'use strict';

    const panel = document.getElementById('readings-panel');

    if (!panel) {
        return;
    }

    const sensorSelect = document.getElementById('sensorId');
    const selectedSensorId = panel.dataset.sensorId || '';

    function restoreSensorSelection() {
        if (sensorSelect) {
            sensorSelect.value = selectedSensorId;
        }
    }

    window.addEventListener('pageshow',restoreSensorSelection);
    restoreSensorSelection();

    if (!selectedSensorId || !panel.dataset.streamUrl) {
        return;
    }

    const tableBody = document.getElementById('readings-table-body');
    const tableWrapper = document.getElementById('readings-table-wrapper');
    const emptyState = document.getElementById('readings-empty-state');
    const scrollHint = document.getElementById('readings-table-scroll-hint');
    const readingsShown = document.getElementById('readings-shown');
    const readingsHeading = document.getElementById('readings-heading');
    const connectionStatus = document.getElementById('readings-connection-status');
    const connectionMessage = document.getElementById('readings-connection-message');

    if (!tableBody || !tableWrapper || !emptyState || !scrollHint
        || !readingsShown || !readingsHeading || !connectionStatus || !connectionMessage) {
        return;
    }

    const connectionTimeoutMilliseconds = 60000;
    const locale = panel.dataset.locale || document.documentElement.lang || 'en';

    const messages = {
        connecting: connectionStatus.dataset.connectingMessage,
        live: connectionStatus.dataset.liveMessage,
        reconnecting: connectionStatus.dataset.reconnectingMessage,
        unavailable: connectionStatus.dataset.unavailableMessage
    };

    let streamUrl;
    let numberFormatter;
    let activeSource = null;
    let connectionTimer = null;
    let lastPresentation = null;
    let paused = false;
    let stopped = false;

    function showStatus(state) {
        const message = messages[state];

        if (!connectionStatus.hidden
            && connectionStatus.dataset.state === state
            && connectionMessage.textContent === message) {
            return;
        }

        connectionStatus.hidden = false;
        connectionStatus.dataset.state = state;
        connectionMessage.textContent = message;
    }

    function clearConnectionTimer() {
        window.clearTimeout(connectionTimer);
        connectionTimer = null;
    }

    function closeStream() {
        const source = activeSource;
        activeSource = null;
        clearConnectionTimer();

        if (source) {
            source.close();
        }
    }

    function stopWithUnavailableStatus() {
        stopped = true;
        closeStream();
        showStatus('unavailable');
    }

    function pauseStream() {
        paused = true;
        closeStream();
        connectionStatus.hidden = true;
    }

    function isCurrentSource(source) {
        return activeSource === source
            && !paused
            && !stopped
            && (!sensorSelect || sensorSelect.value === selectedSensorId);
    }

    function armConnectionTimer(source) {
        if (connectionTimer !== null) {
            return;
        }

        connectionTimer = window.setTimeout(() => {
            connectionTimer = null;

            if (isCurrentSource(source)) {
                stopWithUnavailableStatus();
            }
        },connectionTimeoutMilliseconds);
    }

    if (typeof window.EventSource !== 'function'
        || !Number.isSafeInteger(Number(selectedSensorId))
        || Number(selectedSensorId) <= 0) {
        stopWithUnavailableStatus();
        return;
    }

    try {
        streamUrl = new URL(panel.dataset.streamUrl,document.baseURI);

        if (streamUrl.origin !== window.location.origin
            || !['http:','https:'].includes(streamUrl.protocol)) {
            throw new Error('The reading stream URL must use the current origin.');
        }

        numberFormatter = new Intl.NumberFormat(locale,{
            minimumIntegerDigits: 1,
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
            useGrouping: false,
            roundingMode: 'halfEven'
        });

        if (numberFormatter.resolvedOptions().roundingMode !== 'halfEven') {
            throw new Error('The required number formatting is unavailable.');
        }
    } catch {
        stopWithUnavailableStatus();
        return;
    }

    function isObject(value) {
        return value !== null && typeof value === 'object' && !Array.isArray(value);
    }

    function formatTimestamp(timestamp,offset) {
        const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2}(?::\d{2})?)$/.exec(timestamp);

        if (!parts || parts[7] !== offset) {
            throw new Error('The reading timestamp has an unexpected format.');
        }

        return `${parts[3]}-${parts[2]}-${parts[1]} ${parts[4]}:${parts[5]}:${parts[6]}`;
    }

    function toPresentation(reading) {
        if (!isObject(reading)
            || typeof reading.installationLocation !== 'string'
            || typeof reading.unitSymbol !== 'string'
            || typeof reading.timestamp !== 'string'
            || typeof reading.timeZoneId !== 'string'
            || reading.timeZoneId.trim() === ''
            || typeof reading.offset !== 'string') {
            throw new Error('The reading has an unexpected shape.');
        }

        let value;
        let valueClass;

        if (reading.sensorType === 'TEMPERATURE' || reading.sensorType === 'HUMIDITY') {
            if (!Number.isFinite(reading.numericValue) || reading.booleanValue !== null) {
                throw new Error('The numeric reading has an unexpected value.');
            }

            value = `${numberFormatter.format(reading.numericValue)} ${reading.unitSymbol}`;
            valueClass = reading.sensorType === 'TEMPERATURE'
                ? 'reading-temperature'
                : 'reading-humidity';
        } else if (reading.sensorType === 'MOTION') {
            if (typeof reading.booleanValue !== 'boolean' || reading.numericValue !== null) {
                throw new Error('The motion reading has an unexpected value.');
            }

            value = reading.booleanValue
                ? panel.dataset.motionDetected
                : panel.dataset.motionClear;

            valueClass = reading.booleanValue
                ? 'motion-state motion-detected'
                : 'motion-state motion-clear';
        } else {
            throw new Error('The reading has an unsupported sensor type.');
        }

        return {
            key: JSON.stringify([
                reading.timestamp,
                reading.numericValue,
                reading.booleanValue
            ]),
            location: reading.installationLocation,
            value,
            valueClass,
            timestamp: formatTimestamp(reading.timestamp,reading.offset),
            timezone: `${reading.offset}[${reading.timeZoneId}]`
        };
    }

    function parseSnapshot(data) {
        const snapshot = JSON.parse(data);

        if (!isObject(snapshot)
            || !Number.isSafeInteger(snapshot.sensorId)
            || String(snapshot.sensorId) !== selectedSensorId
            || !Array.isArray(snapshot.readings)
            || snapshot.readings.length > 10) {
            throw new Error('The reading snapshot has an unexpected shape.');
        }

        return snapshot.readings.map(toPresentation);
    }

    function createCell(className,text) {
        const cell = document.createElement('td');
        cell.className = className;
        cell.textContent = text;
        return cell;
    }

    function createRow(reading) {
        const row = document.createElement('tr');
        const locationCell = createCell('reading-location',reading.location);
        const valueCell = createCell('reading-value','');
        const timestampCell = createCell('reading-timestamp',reading.timestamp);
        const timezoneCell = createCell('reading-timezone',reading.timezone);
        const value = document.createElement('span');

        value.className = reading.valueClass;
        value.textContent = reading.value;
        valueCell.append(value);
        row.append(locationCell,valueCell,timestampCell,timezoneCell);

        return row;
    }

    function renderSnapshot(readings) {
        const presentation = JSON.stringify(readings);

        if (presentation === lastPresentation) {
            return;
        }

        const previous = lastPresentation === null
            ? null
            : JSON.parse(lastPresentation);

        const topChanged = previous !== null
            && readings.length > 0
            && readings[0].key !== previous[0]?.key;

        const bottomChanged = topChanged
            && previous.length === 10
            && readings.length === 10
            && previous[9].key !== readings[9].key;

        const fragment = document.createDocumentFragment();

        readings.forEach((reading, index) => {
            const row = createRow(reading);

            if (topChanged && index === 0) {
                row.classList.add('reading-flash-blue');
            }

            if (bottomChanged && index === readings.length - 1) {
                row.classList.add('reading-flash-red');
            }

            fragment.append(row);
        });

        const hasReadings = readings.length > 0;
        const tableHadFocus = tableWrapper.contains(document.activeElement);
        const previousScrollLeft = tableWrapper.scrollLeft;

        tableBody.replaceChildren(fragment);
        tableWrapper.hidden = !hasReadings;
        scrollHint.hidden = !hasReadings;
        emptyState.hidden = hasReadings;
        readingsShown.textContent = String(readings.length);

        if (hasReadings) {
            tableWrapper.scrollLeft = previousScrollLeft;
        } else if (tableHadFocus) {
            readingsHeading.tabIndex = -1;
            readingsHeading.focus({preventScroll: true});
        }

        lastPresentation = presentation;
    }

    function connect() {
        if (paused || stopped || activeSource !== null) {
            return;
        }

        showStatus('connecting');

        let source;

        try {
            source = new EventSource(streamUrl.href);
        } catch {
            stopWithUnavailableStatus();
            return;
        }

        activeSource = source;

        source.addEventListener('readings',event => {
            if (!isCurrentSource(source)) {
                return;
            }

            try {
                const readings = parseSnapshot(event.data);
                renderSnapshot(readings);
                clearConnectionTimer();
                showStatus('live');
            } catch {
                stopWithUnavailableStatus();
            }
        });

        source.addEventListener('error',() => {
            if (!isCurrentSource(source)) {
                return;
            }

            if (source.readyState === EventSource.CLOSED) {
                stopWithUnavailableStatus();
                return;
            }

            showStatus('reconnecting');
            armConnectionTimer(source);
        });

        source.addEventListener('stream-ended',() => {
            if (isCurrentSource(source)) {
                stopWithUnavailableStatus();
            }
        });

        armConnectionTimer(source);
    }

    if (sensorSelect) {
        sensorSelect.addEventListener('change',pauseStream);
    }

    window.addEventListener('pagehide',pauseStream);

    window.addEventListener('pageshow',event => {
        if (!event.persisted) {
            return;
        }

        paused = false;

        if (stopped) {
            showStatus('unavailable');
        } else {
            connect();
        }
    });

    connect();
})();