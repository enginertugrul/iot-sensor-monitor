(() => {
    const firstInvalidControl = document.querySelector('.form-control[aria-invalid="true"]');

    if (firstInvalidControl) {
        firstInvalidControl.focus();
        return;
    }

    const statusFocusTarget = document.querySelector('[data-focus-status]');

    if (statusFocusTarget) {
        statusFocusTarget.focus();
    }
})();