#ifndef F6K_ENGINE_H
#define F6K_ENGINE_H

#ifdef __cplusplus
extern "C" {
#endif

struct Of6kFilm;

enum {
    F6K_OK = 0,
    F6K_E_IO = 1,
    F6K_E_FORMAT = 2,
    F6K_E_IN = 3,
    F6K_E_OUT = 4,
};

struct Of6kFilm *of6k_load_film(const char *flmPath, const char *texdir);
void of6k_free_film(struct Of6kFilm *m);
const char *of6k_film_name(struct Of6kFilm *m);
const char *of6k_film_group(struct Of6kFilm *m);
const char *of6k_film_sub(struct Of6kFilm *m);

int of6k_process(struct Of6kFilm *m, const char *in, const char *out,
                 int scale_denom, int quality);

#ifdef __cplusplus
}
#endif
#endif
