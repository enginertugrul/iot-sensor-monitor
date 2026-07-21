(() => {
    const copyButton = document.querySelector(
        '[data-copy-sensor-token]'
    );

    if (!copyButton) {
        return;
    }

    const tokenInput = document.getElementById(
        copyButton.dataset.copySensorToken
    );

    const copyStatus = document.getElementById(
        'sensorTokenCopyStatus'
    );

    if (!tokenInput || !copyStatus) {
        return;
    }

    function showCopyStatus(message, failed) {
        copyStatus.classList.toggle(
            'form-feedback--success',
            !failed
        );

        copyStatus.classList.toggle(
            'form-feedback--error',
            failed
        );

        copyStatus.textContent = message;
    }

    copyButton.addEventListener('click', async () => {
        try {
            if (!navigator.clipboard?.writeText) {
                throw new Error('Clipboard API unavailable.');
            }

            await navigator.clipboard.writeText(
                tokenInput.value
            );

            showCopyStatus(
                copyButton.dataset.copySuccess,
                false
            );
        } catch {
            tokenInput.focus();
            tokenInput.select();

            showCopyStatus(
                copyButton.dataset.copyFailure,
                true
            );
        }
    });
})();