(() => {
    document
        .querySelectorAll('[data-auto-submit-select]')
        .forEach(select => {
            select.addEventListener('change', () => {
                if (select.value) {
                    select.form.submit();
                    return;
                }

                window.location.href = select.form.action;
            });
        });
})();