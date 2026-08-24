package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("A two-dimensional image-map coordinate")
@Data
public class IrisImageMapOrigin {
    private double x = 0D;
    private double z = 0D;

    public IrisImageMapOrigin(double x, double z) {
        this.x = x;
        this.z = z;
    }
}
