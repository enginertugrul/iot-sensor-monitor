(() => {
    const firstInvalidControl = document.querySelector(
        '.form-control[aria-invalid="true"]'
    );

    if (firstInvalidControl) {
        firstInvalidControl.focus();
    }
})();